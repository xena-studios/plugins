package co.xenastudios.xmoney.ledger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes the writer's database fail on cue, for tests: wraps each connection it opens. The
 * database stays real; only what the writer hears changes.
 */
final class Faults {

    /** While true, every call but close fails, as if the database had gone away. */
    volatile boolean down;
    /** Commits that go through but whose answer is lost: they throw after committing. */
    final AtomicInteger lostCommits = new AtomicInteger();
    /** Statements containing {@link #slowText} wait this long to be prepared, as a slow database would. */
    volatile long slowMillis;
    volatile String slowText = "";
    /**
     * Statements containing {@link #slowText} wait this long to be prepared, ignoring
     * interrupts, as a thread stuck in a socket read would.
     */
    volatile long stubbornMillis;
    /** Connections opened before {@link #killOpen} fail every call, as after MySQL's wait_timeout. */
    private final AtomicInteger opened = new AtomicInteger();
    private volatile int killedBelow;
    /** Statements containing {@link #failText} fail to be prepared, this many times. */
    final AtomicInteger failures = new AtomicInteger();
    volatile String failText = "";

    /** Every connection open now dies; new ones work. */
    void killOpen() {
        killedBelow = opened.get();
    }

    Connection wrap(Connection real) {
        int number = opened.getAndIncrement();
        InvocationHandler handler = (proxy, method, args) -> {
            if (number < killedBelow && !method.getName().equals("close")) {
                throw new SQLException("the connection was closed by the server (a test)");
            }
            return call(real, method, args);
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private Object call(Connection real, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (down && !name.equals("close")) {
            throw new SQLException("the database is down (a test)");
        }
        if (name.equals("prepareStatement") && args != null && args[0] instanceof String sql) {
            if (!slowText.isEmpty() && sql.contains(slowText) && slowMillis > 0) {
                Thread.sleep(slowMillis);
            }
            if (!slowText.isEmpty() && sql.contains(slowText) && stubbornMillis > 0) {
                long until = System.currentTimeMillis() + stubbornMillis;
                boolean interrupted = false;
                while (System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(Math.max(1, until - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!failText.isEmpty() && sql.contains(failText) && failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new SQLException("this statement failed (a test)");
            }
        }
        try {
            Object result = method.invoke(real, args);
            if (name.equals("commit") && lostCommits.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new SQLException("the commit's answer was lost (a test)");
            }
            return result;
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
