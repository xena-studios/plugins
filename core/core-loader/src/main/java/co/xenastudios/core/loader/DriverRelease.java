package co.xenastudios.core.loader;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * Takes out of {@link DriverManager} every JDBC driver its own class loader defined, so a driver
 * loaded in a class loader of its own for one job (core-jvm's {@code IsolatedDriver}) can be
 * let go: H2 and HSQLDB register themselves as their classes load, and DriverManager would hold
 * them, and their class loader, until the server stops.
 *
 * DriverManager lets only code that can see a driver's class take it out, and nothing outside
 * that class loader can. So this class is copied into it ({@code IsolatedDriver} defines these
 * same bytes there) and run from there. Plain Java with nothing but {@code java.*}, as the
 * isolated loader has only the driver and the JDK; R8 keeps it whole (xena/r8/common.pro).
 */
public final class DriverRelease implements Runnable {

    @Override
    public void run() {
        ClassLoader own = DriverRelease.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == own) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException | SecurityException e) {
                    // Then it stays registered until the server stops: a leak, not a failure.
                }
            }
        }
    }
}
