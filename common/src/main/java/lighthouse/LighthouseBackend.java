package lighthouse;

import com.google.bitcoin.core.*;
import com.google.bitcoin.params.RegTestParams;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import javafx.beans.InvalidationListener;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.collections.*;
import lighthouse.files.AppDirectory;
import lighthouse.files.DiskManager;
import lighthouse.protocol.*;
import lighthouse.threading.AffinityExecutor;
import lighthouse.threading.ObservableMirrors;
import lighthouse.wallet.PledgingWallet;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;
import static com.google.common.base.Throwables.getRootCause;
import static java.util.stream.Collectors.toMap;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.MoreBindings.mergeSets;

/**
 * <p>Exposes observable data about pledges and projects that is based on combining the output of a wallet and
 * DiskProjectsManager (local folders) with (when used from the app) data queried from remote project servers.</p>
 *
 * <p>LighthouseBackend is a bit actor-ish: it uses its own thread which owns almost all internal state. Other
 * objects it owns can sometimes use their own threads, but results are always marshalled onto the LighthouseBackend
 * thread before the Observable collections are modified. This design assists with avoiding locking and keeping
 * concurrency manageable. A prior approach based on ordinary threading and locking got too complicated.</p>
 *
 * <p>LighthouseBackend is used in both the GUI app and on the server. In the server case the wallet will typically be
 * empty and projects/pledges are stored on disk only. Ideally, it's connected to a local Bitcoin Core node.</p>
 */
public class LighthouseBackend extends AbstractBlockChainListener {
    private static final Logger log = LoggerFactory.getLogger(LighthouseBackend.class);

    private final DiskManager diskManager;
    public final AffinityExecutor executor;
    private final PeerGroup peerGroup;
    private final PledgingWallet wallet;
    private final CompletableFuture<Boolean> initialized = new CompletableFuture<>();

    private int minPeersForUTXOQuery = 2;

    public static enum Mode {
        CLIENT,
        SERVER
    }
    public final Mode mode;

    /**
     * Is the project currently open for pledges or did it complete successfully? In future we might have EXPIRED here
     * too for deadlined contracts. DiskManager just keeps track of this, doesn't actually calculate the correct answer.
     */
    public static enum ProjectState {
        OPEN,
        ERROR,
        CLAIMED
    }

    public static class CheckStatus {
        public final boolean inProgress;
        @Nullable public final Throwable error;

        private CheckStatus(boolean inProgress, @Nullable Throwable error) {
            this.inProgress = inProgress;
            this.error = error;
        }

        public static CheckStatus inProgress() {
            return new CheckStatus(true, null);
        }

        public static CheckStatus withError(Throwable error) {
            return new CheckStatus(false, error);
        }

        @Override
        public String toString() {
            return "CheckStatus{" +
                    "inProgress=" + inProgress +
                    ", error=" + error +
                    '}';
        }
    }
    private final ObservableMap<Project, CheckStatus> checkStatuses;

    // Non-revoked non-claimed pledges either:
    //  - Fetched from the remote server, which is inherently trusted as it's run by the person you're
    //    trying to give money to
    //  - Checked against the P2P network, which is only semi-trusted but in practice should
    //    work well enough to just keep our UI consistent, which is all we use it for.
    //  - From the users wallet, which are trusted because we created it.
    private final Map<Project, ObservableSet<LHProtos.Pledge>> openPledges;
    // Pledges that don't show up in the UTXO set but did show up in a claim tx we're watching.
    private final Map<Project, ObservableSet<LHProtos.Pledge>> claimedPledges;

    @GuardedBy("this")
    private final Map<String, Project> projectsByUrlPath;

    public LighthouseBackend(Mode mode, PeerGroup peerGroup, AbstractBlockChain chain, PledgingWallet wallet) {
        this(mode, peerGroup, chain, wallet, new AffinityExecutor.ServiceAffinityExecutor("LighthouseBackend"));
    }

    public LighthouseBackend(Mode mode, PeerGroup peerGroup, AbstractBlockChain chain, PledgingWallet wallet, AffinityExecutor executor) {
        this(mode, peerGroup, chain, wallet, new DiskManager(executor), executor);
    }

    public LighthouseBackend(Mode mode, PeerGroup peerGroup, AbstractBlockChain chain, PledgingWallet wallet, DiskManager diskManager, AffinityExecutor executor) {
        this.diskManager = diskManager;
        this.executor = executor;
        this.peerGroup = peerGroup;
        this.openPledges = new HashMap<>();
        this.claimedPledges = new HashMap<>();
        this.wallet = wallet;
        this.mode = mode;
        this.checkStatuses = FXCollections.observableHashMap();
        this.projectsByUrlPath = new HashMap<>();

        if (wallet.getParams() == RegTestParams.get()) {
            setMinPeersForUTXOQuery(1);
            setMaxJitterSeconds(1);
        }

        diskManager.observeProjects(this::onDiskProjectAdded, executor);

        // In server mode watch the apps data directory for new projects being dropped.
        if (mode == Mode.SERVER)
            diskManager.addProjectsDir(AppDirectory.dir());

        // Run initialisation later (not ASAP). This is needed because the disk manager may itself be waiting to fully
        // start up. This odd initialisation sequence is to simplify the addition of event handlers: the backend and
        // disk manager classes can be created and wired together, but if this is done from the AffinityExecutor thread
        // then nothing will happen immediately, meaning that set/list-changed handlers will run for newly loaded data.
        // This can simplify code elsewhere.
        executor.execute(() -> {
            chain.addListener(this, executor);

            // Load pledges found in the wallet. Note that if the user moves or deletes the project file they downloaded,
            // the pledge won't be usable anymore! This is not what users expect; we need to really nail the "import" vs
            // "watch" case. TODO: Make this all work properly for the different cases (probably make redundant copies).
            for (LHProtos.Pledge pledge : wallet.getPledges()) {
                Project project = diskManager.getProjectById(pledge.getProjectId());
                if (project != null) {
                    getOpenPledgesFor(project).add(pledge);
                } else {
                    log.error("Found a pledge in the wallet but could not find the corresponding project: {}", pledge.getProjectId());
                }
            }
            wallet.addOnPledgeHandler((project, pledge) -> {
                log.info("onPledgeHandler: {}", pledge);
                final ObservableSet<LHProtos.Pledge> pledgesFor = getOpenPledgesFor(project);
                pledgesFor.add(pledge);
            }, executor);
            wallet.addOnRevokeHandler(pledge -> {
                Project project = diskManager.getProjectById(pledge.getProjectId());
                if (project != null) {
                    getOpenPledgesFor(project).remove(pledge);
                } else {
                    log.error("Found a pledge in the wallet but could not find the corresponding project: {}", pledge.getProjectId());
                }
            }, executor);

            // Make sure we can spot projects being claimed.
            wallet.addEventListener(new AbstractWalletEventListener() {
                @Override
                public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                    checkPossibleClaimTX(tx);
                }
            }, executor);
            for (Transaction transaction : wallet.getTransactions(false)) {
                Project project = diskManager.getProjectFromClaim(transaction);
                if (project != null) {
                    addClaimConfidenceListener(executor, transaction, project);
                    movePledgesFromOpenToClaimed(transaction, project);
                }
            }

            initialized.complete(true);
        });
    }

    private void addClaimConfidenceListener(AffinityExecutor executor, Transaction transaction, Project project) {
        transaction.getConfidence().addEventListener((t, changeReason) -> {
            log.info("Saw claim tx {} change confidence because {}", t.getHash(), changeReason);
            checkClaimConfidence(t, project);
        }, executor);
    }

    private void checkPossibleClaimTX(Transaction tx) {
        // tx may or may not be a transaction that completes a project we're aware of. We can never really know for
        // sure because of how the Bitcoin protocol works, but we check here to see if the outputs all match the
        // project and if so, we presume that it's completed. Note that 'tx' here comes from the network and might
        // be unconfirmed or unconfirmable at this point, however, we want to update the UI as soon as the claim is
        // seen so the user sees what they expect to see: we can show a confirmations ticker on the screen at the UI
        // level, or the user can just use whatever the destination wallet is to find out when they've got the money
        // to a high enough degree of confidence.
        executor.checkOnThread();
        Project project = diskManager.getProjectFromClaim(tx);
        if (project == null) return;
        log.info("Found claim tx {} with {} inputs for project {}", tx.getHash(), tx.getInputs().size(), project);
        tx.verify();   // Already done but these checks are fast, can't hurt to repeat.
        // We could check that the inputs are all (but one) signed with SIGHASH_ANYONECANPAY here, but it seems
        // overly strict at the moment.
        tx.setPurpose(Transaction.Purpose.ASSURANCE_CONTRACT_CLAIM);
        // Figure out if the claim is good enough to tell the user about yet. Note that our confidence can go DOWN
        // as well as up if the transaction is double spent or there's a re-org that sends it back to being pending.
        checkClaimConfidence(tx, project);
        addClaimConfidenceListener(executor, tx, project);
    }

    private void checkClaimConfidence(Transaction t, Project project) {
        switch (t.getConfidence().getConfidenceType()) {
            case PENDING:
                int seenBy = t.getConfidence().numBroadcastPeers();
                log.info("Claim seen by {} peers", seenBy);
                if (seenBy < peerGroup.getMinBroadcastConnections())
                    break;
                // Fall through ...
            case BUILDING:
                log.info("Claim propagated or mined");
                if (project.getPaymentURL() == null)
                    movePledgesFromOpenToClaimed(t, project);
                else
                    refreshProjectStatusFromServer(project);
                diskManager.setProjectState(project, new ProjectStateInfo(ProjectState.CLAIMED, t.getHash()));
                break;
            case DEAD:
                log.warn("Claim double spent! Overridden by {}", t.getConfidence().getOverridingTransaction());
                diskManager.setProjectState(project, new ProjectStateInfo(ProjectState.ERROR, null));
                break;
            case UNKNOWN:
                break;
        }
    }

    private void movePledgesFromOpenToClaimed(Transaction claim, Project project) {
        executor.checkOnThread();
        List<LHProtos.Pledge> taken = new ArrayList<>();
        for (LHProtos.Pledge pledge : getOpenPledgesFor(project)) {
            if (LHUtils.pledgeAppearsInClaim(project, pledge, claim)) {
                taken.add(pledge);
            }
        }
        getClaimedPledgesFor(project).addAll(taken);
        getOpenPledgesFor(project).removeAll(taken);
    }

    public void waitForInit() {
        checkedGet(initialized);
    }

    private void onDiskProjectAdded(ListChangeListener.Change<? extends Project> change) {
        executor.checkOnThread();
        while (change.next()) {
            if (change.wasUpdated()) {
                // Sometimes we get such updates from the Linux kernel even when all we did was create a file on disk
                // in a directory that's already being monitored due to another project.
                log.info("Project updated: {}", change.getAddedSubList().get(0));
                continue;
            }

            if (change.wasRemoved()) {
                checkState(change.getRemovedSize() == 1);   // DiskManager doesn't batch.
                Project project = change.getRemoved().get(0);
                log.info("Project removed: {}", project);
            }
            if (change.wasAdded()) {
                checkState(change.getAddedSize() == 1);   // DiskManager doesn't batch.
                Project project = change.getAddedSubList().get(0);
                log.info("New project found on disk: {}", project);
                if (mode == Mode.SERVER) {
                    synchronized (this) {
                        URI url = project.getPaymentURL();
                        if (url == null) {
                            log.error("Project found that has no payment URL: cannot work like this!");
                            continue;
                        }
                        projectsByUrlPath.put(url.getPath(), project);
                    }
                }
                // Make sure we keep an eye on the project output scripts so we can spot claim transactions, note
                // that this works even if we never make any pledge ourselves, for example because we are a server.
                // We ask the wallet to track it instead of doing this ourselves because the wallet knows how to do
                // things like watch out for double spends and track chain depth.
                wallet.addWatchedScripts(mapList(project.getOutputs(), TransactionOutput::getScriptPubKey));
                if (project.getPaymentURL() != null && mode == Mode.CLIENT) {
                    log.info("Checking project against server: {}", project);
                    lookupPledgesFromServer(project);
                } else {
                    log.info("Checking newly found project against P2P network: {}", project);
                    ObservableSet<LHProtos.Pledge> unverifiedPledges = diskManager.getPledgesOrCreate(project);
                    unverifiedPledges.addListener((SetChangeListener<LHProtos.Pledge>) change2 -> diskPledgesChanged(change2, project));
                    checkPledgesAgainstP2PNetwork(project, unverifiedPledges, true);
                }
            }
        }
    }

    private void diskPledgesChanged(SetChangeListener.Change<? extends LHProtos.Pledge> change, Project project) {
        executor.checkOnThread();
        if (change.wasRemoved()) {
            LHProtos.Pledge walletPledge = wallet.getPledgeFor(project);
            LHProtos.Pledge removedPledge = change.getElementRemoved();
            if (walletPledge != null && walletPledge.equals(removedPledge)) {
                // Pledge file was removed from disk, but we may have another copy in the wallet, in this case the disk
                // copy is redundant and if the user or project owner blows it away (e.g. via a shared dropbox), no harm
                // done. Maybe we should auto-restore it to remind the user that they have to revoke it properly, or
                // show them a message?
                log.info("Pledge in wallet was removed from disk, ignoring.");
            } else {
                // Bye bye .... even if the pledge was claimed, we're about to lose our knowlege of it because the
                // user removed it from disk, so we can't keep track of it reliably afterwards anyway.
                openPledges.get(project).remove(removedPledge);
                getClaimedPledgesFor(project).remove(removedPledge);
            }
        }
        if (change.wasAdded()) {
            final LHProtos.Pledge added = change.getElementAdded();
            if (!isPledgeKnown(added)) {
                log.info("New pledge found on disk for {}", project);
                // Jitter to give the dependency txns time to propagate in case somehow our source of pledges
                // is faster than the P2P network (e.g. local network drive or in regtesting mode).
                jitteredExecute(() -> checkPledgeAgainstP2PNetwork(project, added), TX_PROPAGATION_TIME_SECS);
            }
        }
    }

    private boolean isPledgeKnown(LHProtos.Pledge pledge) {
        executor.checkOnThread();
        if (mode == Mode.CLIENT && wallet.wasPledgeRevoked(pledge)) return true;
        for (ObservableSet<LHProtos.Pledge> set : openPledges.values()) if (set.contains(pledge)) return true;
        for (ObservableSet<LHProtos.Pledge> set : claimedPledges.values()) if (set.contains(pledge)) return true;
        return false;
    }

    // Completes with either the given pledge, or with null if it failed verification (reason not available here).
    private CompletableFuture<LHProtos.Pledge> checkPledgeAgainstP2PNetwork(Project project, LHProtos.Pledge pledge) {
        // Can be on any thread.
        return checkPledgesAgainstP2PNetwork(project, FXCollections.observableSet(pledge), false).thenApply(results ->
        {
            if (results.isEmpty())
                return null;
            else
                return results.iterator().next();
        });
    }

    // Completes with the set of pledges that passed verification.
    // If checkingAllPledges is false then pledges contains a single item, otherwise it contains all pledges for the
    // project together.
    private CompletableFuture<Set<LHProtos.Pledge>> checkPledgesAgainstP2PNetwork(Project project,
                                                                                  final ObservableSet<LHProtos.Pledge> pledges,
                                                                                  boolean checkingAllPledges) {
        if (pledges.isEmpty()) {
            log.info("No pledges to check");
            return CompletableFuture.completedFuture(Collections.EMPTY_SET);
        }
        if (!checkingAllPledges)
            checkState(pledges.size() == 1);
        CompletableFuture<Set<LHProtos.Pledge>> result = new CompletableFuture<>();
        if (mode == Mode.CLIENT) {
            // If we're running inside the desktop app, forbid pledges with dependencies for now. It simplifies things:
            // the app is supposed to broadcast and allow a dependency tx to settle before creating a pledge file, so
            // we should never hit the exceptional case below when the protocol is being followed. We could call
            // broadcastDependenciesOf() instead when we first become aware of the pledge if we wanted to change this
            // in future.
            for (LHProtos.Pledge pledge : pledges) {
                if (pledge.getTransactionsCount() != 1)
                    result.completeExceptionally(new Ex.TooManyDependencies(pledge.getTransactionsCount()));
            }
        }
        executor.executeASAP(() -> {
            log.info("Checking {} pledge(s) against P2P network for {}", pledges.size(), project);
            markAsInProgress(project);
            ListenableFuture<List<Peer>> peerFuture = peerGroup.waitForPeersOfVersion(minPeersForUTXOQuery, GetUTXOsMessage.MIN_PROTOCOL_VERSION);
            if (!peerFuture.isDone())
                log.info("Waiting to find {} peers that support getutxo", minPeersForUTXOQuery);
            Futures.addCallback(peerFuture, new FutureCallback<List<Peer>>() {
                @Override
                public void onSuccess(@Nullable List<Peer> peers) {
                    // On backend thread here. We must be because we need to ensure only a single UTXO query is in flight
                    // on the P2P network at once and only triggering such queries from the backend thread is a simple
                    // way to achieve that. It means we block the backend thread until the query completes or times out
                    // but that's OK - the other tasks can wait.
                    executor.checkOnThread();
                    checkNotNull(peers);
                    // Do a fast delete of any peers that claim they don't support NODE_GETUTXOS. We ensure we always
                    // find nodes that support it elsewhere.
                    int origSize = peers.size();
                    peers = new ArrayList<>(peers);
                    peers.removeIf(peer -> !peer.getPeerVersionMessage().isGetUTXOsSupported());
                    if (peers.isEmpty()) {
                        Exception ex = new Exception("No nodes of high enough version advertised NODE_GETUTXOS");
                        log.error(ex.getMessage());
                        checkStatuses.put(project, CheckStatus.withError(ex));
                        result.completeExceptionally(ex);
                        return;
                    }
                    if (peers.size() != origSize)
                        log.info("Dropped {} peers for not supporting NODE_GETUTXOS, now have {}", peers.size() - origSize, peers.size());
                    doUTXOLookupsForPledges(project, pledges, peers, checkingAllPledges, result);
                }

                @Override
                public void onFailure(Throwable t) {
                    // This should actually never happen as the peer future cannot fail.
                    log.error("Failed to locate peers", t);
                    markAsErrored(project, t);
                    result.completeExceptionally(t);
                }
            }, executor);
        });
        return result;
    }

    private void markAsInProgress(Project project) {
        log.info("Checking in progress: {}", project);
        checkStatuses.put(project, CheckStatus.inProgress());
    }

    private void markAsErrored(Project project, Throwable ex) {
        log.info("Checking had an error: {}", project);
        checkStatuses.put(project, CheckStatus.withError(getRootCause(ex)));
    }

    private void markAsCheckDone(Project project) {
        log.info("Checking done: {}", project);
        checkStatuses.remove(project);
    }

    private void doUTXOLookupsForPledges(Project project, ObservableSet<LHProtos.Pledge> pledges, List<Peer> peers,
                                         boolean checkingAllPledges, CompletableFuture<Set<LHProtos.Pledge>> result) {
        executor.checkOnThread();
        try {
            // The multiplexor issues the same query to multiple peers and verifies they're all consistent.
            log.info("Querying {} peers", peers.size());
            PeerUTXOMultiplexor multiplexor = new PeerUTXOMultiplexor(peers);
            // The batcher queues up queries from project.verifyPledge and combines them into a single query, to
            // speed things up and minimise network traffic.
            BatchingUTXOSource utxoSource = new BatchingUTXOSource(multiplexor);
            List<CompletableFuture<LHProtos.Pledge>> futures = new ArrayList<>(pledges.size());
            for (LHProtos.Pledge pledge : pledges)
                futures.add(project.verifyPledge(utxoSource, pledge));
            try {
                utxoSource.run();   // Actually send the query.
                futureOfFutures(futures).get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Some peer(s) didn't get back to us fast enough, they'll be filtered out below.
            }
            Set<TransactionOutPoint> allOutpoints = checkingAllPledges ? new HashSet<>() : getAllPledgedOutPointsFor(project);
            List<LHProtos.Pledge> verifiedPledges = new ArrayList<>(futures.size());
            for (CompletableFuture<LHProtos.Pledge> future : futures) {
                if (!future.isDone()) {
                    log.warn("getutxo lookup failed or timed out: {}", future);
                    continue;
                }
                try {
                    LHProtos.Pledge pledge = future.get();
                    // Check that this pledge is not spending the same outputs as any other accepted pledge.
                    // Note that project.verifyPledge already called fastSanityCheck -> tx.verify() which verified
                    // the pledge tx does not itself have the same outpoints repeated in the same transaction/pledge.
                    // If it did then we exit on the above line and end up in the ExecutionException branches.
                    Transaction tx = project.fastSanityCheck(pledge);
                    for (TransactionInput input : tx.getInputs()) {
                        if (allOutpoints.contains(input.getOutpoint()))
                            throw new ExecutionException(new VerificationException.DuplicatedOutPoint());
                        allOutpoints.add(input.getOutpoint());
                    }
                    verifiedPledges.add(pledge);
                } catch (ExecutionException e) {
                    // Unless pledge was merely revoked, we will expose the error to the UI and stop processing pledges.
                    // We don't continue and try to process the rest.
                    if (!(getRootCause(e) instanceof Ex.UnknownUTXO))
                        throw e;
                }
            }
            log.info("{} of {} pledges verified (were not revoked/claimed)", verifiedPledges.size(), pledges.size());
            syncPledges(project, pledges, verifiedPledges, checkingAllPledges);
            markAsCheckDone(project);
            result.complete(new HashSet<>(verifiedPledges));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error looking up UTXOs", e);
            markAsErrored(project, e);
            result.completeExceptionally(e);
        }
    }

    private HashSet<TransactionOutPoint> getAllPledgedOutPointsFor(Project project) {
        HashSet<TransactionOutPoint> results = new HashSet<>();
        for (LHProtos.Pledge pledge : getOpenPledgesFor(project)) {
            Transaction tx = project.fastSanityCheck(pledge);
            for (TransactionInput input : tx.getInputs()) {
                TransactionOutPoint op = input.getOutpoint();
                if (results.contains(op))
                    throw new VerificationException.DuplicatedOutPoint();
                results.add(op);
            }
        }
        return results;
    }

    private CompletableFuture<Void> lookupPledgesFromServer(Project project) {
        // Sigh, wish Java had proper co-routines (there's a lib that does it nicely but is overkill for this function).
        // This is messy because we want to overlap multiple lookups and thenAcceptAsync doesn't work how you'd think
        // it works (it will block the backend thread whilst waiting for the getStatus call to complete).
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            markAsInProgress(project);
            project.getStatus(wallet).whenCompleteAsync((status, ex) -> {
                if (ex != null) {
                    markAsErrored(project, ex);
                    future.completeExceptionally(ex);
                } else {
                    // Status contains a new list of pledges. We should update our own observable list by touching it
                    // as little as possible. This ensures that as updates flow through to the UI any animations look
                    // good (as opposed to total replacement which would animate poorly).
                    log.info("Processing project status:\n{}", status);
                    syncPledges(project, new HashSet<>(status.getPledgesList()), status.getPledgesList(), true);
                    // Server's view of the truth overrides our own for UI purposes, as we might have failed to
                    // observe the contract/claim tx if the user imported the project post-claim.
                    if (status.hasClaimedBy() && diskManager.getProjectState(project).state != ProjectState.CLAIMED) {
                        diskManager.setProjectState(project, new ProjectStateInfo(ProjectState.CLAIMED,
                                new Sha256Hash(status.getClaimedBy().toByteArray())));
                    }
                    markAsCheckDone(project);
                    future.complete(null);
                }
            }, executor);
        });
        return future;
    }

    /** Invokes a manual refresh by going back to the server. Can be called from any thread. */
    public CompletableFuture<Void> refreshProjectStatusFromServer(Project project) {
        return lookupPledgesFromServer(project);
    }

    private void syncPledges(Project forProject, Set<LHProtos.Pledge> testedPledges, List<LHProtos.Pledge> verifiedPledges, boolean moveMissing) {
        executor.checkOnThread();
        final ObservableSet<LHProtos.Pledge> curOpenPledges = getOpenPledgesFor(forProject);

        // Build a map of pledgehash->pledge so we can dedupe server-scrubbed pledges.
        Map<Sha256Hash, LHProtos.Pledge> hashes = curOpenPledges.stream().collect(
                toMap(p -> Sha256Hash.create(p.toByteArray()), p -> p)
        );

        // Try and update openPledges/claimedPledges with minimal touching, so animations work right.

        Set<LHProtos.Pledge> newlyOpen = new HashSet<>(verifiedPledges);
        newlyOpen.removeAll(curOpenPledges);
        if (mode == Mode.CLIENT) {
            // Servers should of course ideally not give us revoked pledges, but it may take a bit of time for the
            // server to notice, especially because currently we wait for a block confirmation before noticing the
            // double spends. So there can be a window of time in which we know we revoked our own pledge, but the
            // server keeps sending it to us.
            newlyOpen.removeIf(wallet::wasPledgeRevoked);
            // Remove if this is a scrubbed version of a pledge we already have i.e. because we created it, uploaded it
            // and are now seeing it come back to us.
            newlyOpen.removeIf(pledge ->
                            pledge.hasOrigHash() && hashes.get(hashFromPledge(pledge)) != null
            );
        }
        curOpenPledges.addAll(newlyOpen);
        Set<LHProtos.Pledge> newlyInvalid = new HashSet<>(testedPledges);
        newlyInvalid.removeAll(verifiedPledges);
        curOpenPledges.removeAll(newlyInvalid);
        // A pledge that's missing might be claimed. See if we know about that here.
        if (forProject.getPaymentURL() == null) {
            Transaction claim = getClaimForProject(forProject);
            if (claim != null) {
                Set<LHProtos.Pledge> newlyClaimed = new HashSet<>(newlyInvalid);
                newlyClaimed.removeIf(pledge -> !LHUtils.pledgeAppearsInClaim(forProject, pledge, claim));
                ObservableSet<LHProtos.Pledge> cpf = getClaimedPledgesFor(forProject);
                cpf.addAll(newlyClaimed);
            }
        }
    }

    private ObservableSet<LHProtos.Pledge> getClaimedPledgesFor(Project forProject) {
        executor.checkOnThread();
        ObservableSet<LHProtos.Pledge> result = claimedPledges.get(forProject);
        if (result == null) {
            result = FXCollections.observableSet();
            claimedPledges.put(forProject, result);
        }
        return result;
    }

    @Nullable
    private Transaction getClaimForProject(Project forProject) {
        ProjectStateInfo state = diskManager.getProjectState(forProject);
        if (state.state == ProjectState.CLAIMED)
            return wallet.getTransaction(state.claimedBy);
        else
            return null;
    }

    /** Returns a new read-only set that has changes applied using the given executor. */
    public ObservableList<Project> mirrorProjects(AffinityExecutor executor) {
        return diskManager.mirrorProjects(executor);
    }

    public Project saveProject(Project project) throws IOException {
        return diskManager.saveProject(project.getProto(), project.getTitle());
    }

    /** Adds the given file as a project: the containing directory will be watched for pledges. */
    public void addProjectFile(Path file) {
        diskManager.addProjectFile(file);
    }

    /**
     * Returns a read only observable list of unclaimed/unrevoked pledges that updates when the project is refreshed
     * or new pledges become visible on disk. May block waiting for the backend.
     */
    public ObservableSet<LHProtos.Pledge> mirrorOpenPledges(Project forProject, AffinityExecutor executor) {
        // Must build the mirror on the backend thread because otherwise it might change whilst we're doing the
        // initial copy to fill it up.
        return this.executor.fetchFrom(() -> ObservableMirrors.mirrorSet(getOpenPledgesFor(forProject), executor));
    }

    /**
     * Returns a read only observable list of claimed pledges. May block waiting for the backend.
     */
    public ObservableSet<LHProtos.Pledge> mirrorClaimedPledges(Project forProject, AffinityExecutor executor) {
        // Must build the mirror on the backend thread because otherwise it might change whilst we're doing the
        // initial copy to fill it up.
        return this.executor.fetchFrom(() -> ObservableMirrors.mirrorSet(getClaimedPledgesFor(forProject), executor));
    }

    @Nullable
    public Project getProjectById(String id) {
        return diskManager.getProjectById(id);
    }

    private ObservableSet<LHProtos.Pledge> getOpenPledgesFor(Project forProject) {
        executor.checkOnThread();
        ObservableSet<LHProtos.Pledge> result = openPledges.get(forProject);
        if (result == null) {
            result = FXCollections.observableSet();
            openPledges.put(forProject, result);
        }
        return result;
    }

    /** Returns a reactive property that sums up the total value of all open pledges. */
    @SuppressWarnings("unchecked")
    public LongProperty makeTotalPledgedProperty(Project project, AffinityExecutor executor) {
        ObservableSet<LHProtos.Pledge> one = mirrorOpenPledges(project, executor);
        ObservableSet<LHProtos.Pledge> two = mirrorClaimedPledges(project, executor);
        return bindTotalPledgedProperty(mergeSets(one, two));
    }

    public ObservableMap<Project, CheckStatus> mirrorCheckStatuses(AffinityExecutor executor) {
        return this.executor.fetchFrom(() -> ObservableMirrors.mirrorMap(checkStatuses, executor));
    }

    /** Returns a property calculated from the given list, with no special mirroring setup. */
    public static LongProperty bindTotalPledgedProperty(ObservableSet<LHProtos.Pledge> pledges) {
        // We must ensure that the returned property keeps a strong reference to pledges, in case it's the only one.
        return new SimpleLongProperty(0) {
            private ObservableSet<LHProtos.Pledge> pledgesRef = pledges;

            // This should probably be done differently (as a lazy binding?) but I doubt it matters.
            {
                pledgesRef.addListener((InvalidationListener) o -> {
                    update();
                });
                update();
            }

            private void update() {
                long total = 0;
                for (LHProtos.Pledge pledge : pledgesRef) {
                    total += pledge.getTotalInputValue();
                }
                set(total);
            }
        };
    }

    @Override
    public void notifyNewBestBlock(StoredBlock block) throws VerificationException {
        executor.checkOnThread();
        // TODO: Fix this so we just watch for revocation transactions via Bloom filtering.
        // Don't bother with pointless recalculations until we're caught up with the chain tip.
        if (block.getHeight() > peerGroup.getMostCommonChainHeight() - 2) {
            log.info("New block found, refreshing pledges");
            for (Project project : diskManager.getProjects()) {
                // In the app, use a new block as a hint to go back and ask the server for an update (e.g. in case
                // any pledges were revoked). This also ensures the project page can be left open and it'll update from
                // time to time, which is nice if you just want it running in the corner of a room or on a projector,
                // etc.
                if (mode == Mode.CLIENT && project.getPaymentURL() != null) {
                    jitteredServerRequery(project);
                } else {
                    jitteredP2PRequery(project);
                }
            }
        }
    }

    private static final int BLOCK_PROPAGATION_TIME_SECS = 30;    // 90th percentile block propagation times ~15 secs
    private static final int TX_PROPAGATION_TIME_SECS = 5;  // 90th percentile tx propagation times ~3 secs
    private int maxJitterSeconds = BLOCK_PROPAGATION_TIME_SECS;

    public int getMaxJitterSeconds() {
        return maxJitterSeconds;
    }

    public void setMaxJitterSeconds(int maxJitterSeconds) {
        this.maxJitterSeconds = maxJitterSeconds;
    }

    private void jitteredP2PRequery(Project project) {
        jitteredExecute(() -> {
            ObservableSet<LHProtos.Pledge> pledgesFor = diskManager.getPledgesFor(project);
            if (pledgesFor != null)
                checkPledgesAgainstP2PNetwork(project, pledgesFor, true /* remove invalid as we're checking all pledges */);
        }, 15);
    }

    private void jitteredServerRequery(Project project) {
        jitteredExecute(() -> refreshProjectStatusFromServer(project), 15);
    }

    // Always wait at least baseSeconds to allow for block propagation and processing of revocations server-side
    // and then smear requests over another baseSeconds.
    private void jitteredExecute(Runnable runnable, int baseSeconds) {
        if (executor instanceof AffinityExecutor.ServiceAffinityExecutor) {
            ScheduledExecutorService service = ((AffinityExecutor.ServiceAffinityExecutor) executor).service;
            int jitterSeconds = Math.min(maxJitterSeconds, baseSeconds + (int) (Math.random() * baseSeconds));
            log.info("Scheduling execution in {} seconds", jitterSeconds);
            service.schedule(runnable, jitterSeconds, TimeUnit.SECONDS);
        } else {
            runnable.run();
        }
    }

    /**
     * Used by the server when a pledge arrives via HTTP[S].
     *
     * Does some fast stateless checks the given pledge on the calling thread and then hands off to the backend
     * thread. The backend broadcasts the pledge's dependencies, if any, and then does a revocation check. Once all
     * the checks pass, the pledge will show up in the projects pledge list.
     *
     * It may seem pointless to do a revocation check after broadcasting dependencies. However, the dependencies may
     * themselves be double spent and the remote nodes may either notice this and send us a reject message (which we
     * ignore), or, not notice and at that point the dependencies would be considered as orphans (which we also won't
     * notice). However once we query the pledge itself, we'll discover it didn't enter the mempool UTXO set because
     * the dependencies didn't connect to a UTXO and not bother saving it to disk as result.
     *
     * The reason we broadcast dependencies on behalf of the client is so we can return a success/failure code: if the
     * app gets back a HTTP 200 OK, the pledge should be considered valid. But if the client had to broadcast his own
     * transactions then there's a race because the app doesn't know if the server saw the dependencies yet, and might
     * get a bogus rejection.
     *
     * If there's an error, the returned future either completes exceptionally OR returns null.
     */
    public CompletableFuture<LHProtos.Pledge> submitPledge(Project project, LHProtos.Pledge pledge) {
        // Can be on any thread.
        CompletableFuture<LHProtos.Pledge> result = new CompletableFuture<>();
        try {
            project.fastSanityCheck(pledge);
            // TODO: Check that the amount pledged (claimed to to be pledged: that claim will be checked below) does not push us over the goal.
            // Maybe broadcast the dependencies first.
            if (pledge.getTransactionsCount() > 1)
                result = broadcastDependenciesOf(pledge);
            else
                result.complete(null);
            result = result.thenCompose(a ->
                // Once dependencies (if any) are handled, start the check process. On any thread here.
                checkPledgeAgainstP2PNetwork(project, pledge)
            );
            result = result.thenApply(a ->
                // Finally, save to disk. This will cause a notification of a new pledge to happen but we'll end
                // up ignoring it because we'll see we already loaded and verified it.
                savePledge(pledge)
            );
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private static LHProtos.Pledge savePledge(LHProtos.Pledge pledge) {
        try {
            // Can be on any thread.
            final byte[] bits = pledge.toByteArray();
            Sha256Hash hash = Sha256Hash.create(bits);
            // This file name is not very helpful for sysadmins. Perhaps if we scrub the metadata enough we can make a
            // better one, e.g. with the users contact details in.
            String filename = hash.toString() + DiskManager.PLEDGE_FILE_EXTENSION;
            // Use a temp file and rename to disallow allow partially visible pledges.
            Path path = AppDirectory.dir().resolve(filename + ".tmp");
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path))) {
                log.info("Saving pledge to disk as {}", filename);
                stream.write(bits);
            }
            Files.move(path, AppDirectory.dir().resolve(filename));
            return pledge;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<LHProtos.Pledge> broadcastDependenciesOf(LHProtos.Pledge pledge) {
        checkArgument(pledge.getTransactionsCount() > 1);
        CompletableFuture<LHProtos.Pledge> result = new CompletableFuture<>();
        executor.executeASAP(() -> {
            try {
                List<ByteString> txnBytes = pledge.getTransactionsList().subList(0, pledge.getTransactionsCount() - 1);
                if (txnBytes.size() > 5) {
                    // We don't accept ridiculous number of dependency txns. Even this is probably too much.
                    result.completeExceptionally(new Ex.TooManyDependencies(txnBytes.size()));
                } else {
                    log.info("Broadcasting {} provided pledge dependencies", txnBytes.size());
                    for (ByteString txnByte : txnBytes) {
                        Transaction tx = new Transaction(wallet.getParams(), txnByte.toByteArray());
                        // Wait for each broadcast in turn. In the local node case this will complete immediately. In the
                        // case of remote nodes (maybe we should forbid this later), it may block for a few seconds whilst
                        // the transactions propagate.
                        peerGroup.broadcastTransaction(tx).get(30, TimeUnit.SECONDS);
                    }
                    result.complete(pledge);
                }
            } catch (InterruptedException | TimeoutException | ExecutionException | ProtocolException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public int getMinPeersForUTXOQuery() {
        return minPeersForUTXOQuery;
    }

    public void setMinPeersForUTXOQuery(int minPeersForUTXOQuery) {
        this.minPeersForUTXOQuery = minPeersForUTXOQuery;
    }

    public static class ProjectStateInfo {
        public final ProjectState state;
        @Nullable public final Sha256Hash claimedBy;

        public ProjectStateInfo(ProjectState state, @Nullable Sha256Hash claimedBy) {
            this.state = state;
            this.claimedBy = claimedBy;
        }

        public ProjectState getState() {
            return state;
        }
    }

    public ObservableMap<String, ProjectStateInfo> mirrorProjectStates(AffinityExecutor runChangesIn) {
        return diskManager.mirrorProjectStates(runChangesIn);
    }

    public synchronized Project getProjectFromURL(URI uri) {
        return projectsByUrlPath.get(uri.getPath());
    }
}
