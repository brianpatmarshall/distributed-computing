package example;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FlakyWorker implements Watcher {

    private static final String WORKERS_PATH = "/managed_workers";
    private static final int SESSION_TIMEOUT = 3000;
    private static final float FAILURE_CHANCE = 0.1f;

    private final String zookeeperAddress;
    private final String workerId;
    private final Random random = new Random();
    private ZooKeeper zooKeeper;

    public FlakyWorker(String zookeeperAddress) {
        this.zookeeperAddress = zookeeperAddress;
        this.workerId = "worker-" + ProcessHandle.current().pid();
    }

    public void start() throws IOException, InterruptedException, KeeperException {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        this.zooKeeper = new ZooKeeper(zookeeperAddress, SESSION_TIMEOUT, event -> {
            if (event.getState() == Event.KeeperState.SyncConnected) {
                connectedLatch.countDown();
            }
        });

        if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
            System.exit(1);
        }

        zooKeeper.create(
                WORKERS_PATH + "/w_",
                workerId.getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL);

        doWork();
    }

    private void doWork() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (random.nextFloat() < FAILURE_CHANCE) {
                System.exit(1);
            }
        }
    }

    @Override
    public void process(WatchedEvent event) {
    }

    public static void main(String[] args) throws Exception {
        String zkAddress = args.length > 0 ? args[0] : "localhost:2181";
        new FlakyWorker(zkAddress).start();
    }
}
