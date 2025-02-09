package com.amazon.jenkins.ec2fleet;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.util.DaemonThreadFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keep {@link hudson.slaves.NodeProvisioner.PlannedNode#future} not resolved until node will not be online
 * or timeout reached.
 * <p>
 * Default Jenkins node capacity planner {@link hudson.slaves.NodeProvisioner.Strategy} count planned nodes
 * as available capacity, but exclude offline computers {@link Computer#isOnline()} from available capacity.
 * Because EC2 instance requires some time when it was added into fleet to start up, next situation happens:
 * plugin add described capacity as node into Jenkins pool, but Jenkins keeps it as offline as no way to connect,
 * during time when node is offline, Jenkins will try to request more nodes from plugin as offline nodes
 * excluded from capacity.
 * <p>
 * This class fix this situation and keep planned node until instance is really online, so Jenkins planner
 * count planned node as available capacity and doesn't request more.
 * <p>
 * Before each wait it will try to {@link Computer#connect(boolean)}, because by default Jenkins is trying to
 * make a few short interval reconnection initially (when EC2 instance still is not ready) after that
 * with big interval, experiment shows a few minutes and more.
 * <p>
 * Based on https://github.com/jenkinsci/ec2-plugin/blob/master/src/main/java/hudson/plugins/ec2/EC2Cloud.java#L640
 *
 * @see EC2FleetCloud
 * @see EC2FleetNode
 */
@SuppressWarnings("WeakerAccess")
@ThreadSafe
class EC2FleetOnlineChecker implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(EC2FleetOnlineChecker.class.getName());
    // use daemon thread, so no problem when stop jenkins
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

    public static void start(final Node node, final CompletableFuture<Node> future, final long timeout, final long interval) {
        EXECUTOR.execute(new EC2FleetOnlineChecker(node, future, timeout, interval));
    }

    private final long start;
    private final Node node;
    private final CompletableFuture<Node> future;
    private final long timeout;
    private final long interval;

    private EC2FleetOnlineChecker(
            final Node node, final CompletableFuture<Node> future, final long timeout, final long interval) {
        this.start = System.currentTimeMillis();
        this.node = node;
        this.future = future;
        this.timeout = timeout;
        this.interval = interval;
    }

    @Override
    public void run() {
        if (future.isCancelled()) {
            return;
        }

        if (timeout < 1 || interval < 1) {
            future.complete(node);
            LOGGER.log(Level.INFO, String.format("Node '%s' connection check disabled. Resolving planned node", node.getDisplayName()));
            return;
        }

        final Computer computer = node.toComputer();
        if (computer != null) {
            if (computer.isOnline()) {
                future.complete(node);
                LOGGER.log(Level.INFO, String.format("Node '%s' connected. Resolving planned node", node.getDisplayName()));
                return;
            }
        }

        if (System.currentTimeMillis() - start > timeout) {
            future.completeExceptionally(new IllegalStateException(
                    "Failed to provision node. Could not connect to node '" + node.getDisplayName() + "' before timeout (" + timeout + "ms)"));
            return;
        }

        if (computer == null) {
            LOGGER.log(Level.INFO, String.format("No connection to node '%s'. Waiting before retry", node.getDisplayName()));
        } else {
            computer.connect(false);
            LOGGER.log(Level.INFO, String.format("No connection to node '%s'. Attempting to connect and waiting before retry", node.getDisplayName()));
        }
        EXECUTOR.schedule(this, interval, TimeUnit.MILLISECONDS);
    }

}
