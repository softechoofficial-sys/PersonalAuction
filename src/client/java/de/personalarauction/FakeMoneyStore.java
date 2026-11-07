package de.personalarauction;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistenter, rein lokaler Fake-Kontostand für Build 146. */
public final class FakeMoneyStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("personalauction-fake-money.properties");
    private static BigDecimal balance = BigDecimal.ZERO;
    private static boolean initialized;

    private FakeMoneyStore() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (!Files.exists(FILE)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            properties.load(input);
            balance = new BigDecimal(properties.getProperty("balance", "0"));
            if (balance.signum() < 0) balance = BigDecimal.ZERO;
        } catch (Exception exception) {
            balance = BigDecimal.ZERO;
            System.err.println("[PersonalAuction] Fake-Guthaben konnte nicht geladen werden: " + exception.getMessage());
        }
    }

    public static synchronized void addPayment(String compactAmount) {
        initialize();
        try {
            BigDecimal amount = parse(compactAmount);
            if (amount.signum() <= 0 || amount.precision() > 30) return;
            balance = balance.add(amount);
            save();
        } catch (RuntimeException exception) {
            System.err.println("[PersonalAuction] Ungültige Fake-Geld-Zahlung ignoriert.");
        }
    }

    public static synchronized BigDecimal getBalance() {
        initialize();
        return balance;
    }

    /** Removes a successfully paid amount from the local balance. */
    public static synchronized void removePayment(String compactAmount) {
        initialize();
        try {
            BigDecimal amount = parse(compactAmount);
            if (amount.signum() <= 0) return;
            balance = balance.subtract(amount).max(BigDecimal.ZERO);
            save();
        } catch (RuntimeException exception) {
            System.err.println("[PersonalAuction] Ungültiger Auszahlungsbetrag ignoriert.");
        }
    }

    public static synchronized String formattedBalance() {
        BigDecimal value = getBalance();
        String[] suffixes = {"", "k", "M", "B", "T"};
        BigDecimal divisor = BigDecimal.ONE;
        int suffix = 0;
        while (value.abs().compareTo(divisor.multiply(BigDecimal.valueOf(1000))) >= 0 && suffix < suffixes.length - 1) {
            divisor = divisor.multiply(BigDecimal.valueOf(1000));
            suffix++;
        }
        BigDecimal shown = value.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return shown.toPlainString() + suffixes[suffix];
    }

    private static BigDecimal parse(String input) {
        String value = input.trim().replace(",", ".");
        BigDecimal multiplier = BigDecimal.ONE;
        if (value.isEmpty()) throw new NumberFormatException();
        char last = Character.toLowerCase(value.charAt(value.length() - 1));
        switch (last) {
            case 'k' -> { multiplier = BigDecimal.valueOf(1_000L); value = value.substring(0, value.length() - 1); }
            case 'm' -> { multiplier = BigDecimal.valueOf(1_000_000L); value = value.substring(0, value.length() - 1); }
            case 'b' -> { multiplier = BigDecimal.valueOf(1_000_000_000L); value = value.substring(0, value.length() - 1); }
            case 't' -> { multiplier = BigDecimal.valueOf(1_000_000_000_000L); value = value.substring(0, value.length() - 1); }
            default -> { }
        }
        return new BigDecimal(value).multiply(multiplier);
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("balance", balance.toPlainString());
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                properties.store(output, "PersonalAuction local fake balance");
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Fake-Guthaben konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }
}
