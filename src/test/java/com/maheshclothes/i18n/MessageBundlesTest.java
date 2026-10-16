package com.maheshclothes.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the English and Marathi text files in step: same keys, same placeholders, real Devanagari. */
class MessageBundlesTest {

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageBundlesTest.class.getResourceAsStream("/i18n/" + name)) {
            assertTrue(in != null, name + " is missing from the classpath");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private static TreeSet<String> placeholders(String s) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void bothLanguagesDefineTheSameKeys() throws IOException {
        Properties en = load("messages.properties");
        Properties mr = load("messages_mr.properties");
        TreeSet<String> onlyEn = new TreeSet<>(en.stringPropertyNames());
        onlyEn.removeAll(mr.stringPropertyNames());
        TreeSet<String> onlyMr = new TreeSet<>(mr.stringPropertyNames());
        onlyMr.removeAll(en.stringPropertyNames());
        assertEquals("[]", onlyEn.toString(), "keys missing in Marathi");
        assertEquals("[]", onlyMr.toString(), "keys missing in English");
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws IOException {
        Properties en = load("messages.properties");
        Properties mr = load("messages_mr.properties");
        for (String k : en.stringPropertyNames()) {
            assertEquals(placeholders(en.getProperty(k)), placeholders(mr.getProperty(k)), "placeholders differ for " + k);
        }
    }

    @Test
    void everyMessageWithPlaceholdersIsAValidMessageFormat() throws IOException {
        for (String file : new String[]{"messages.properties", "messages_mr.properties"}) {
            Properties p = load(file);
            for (String k : p.stringPropertyNames()) {
                String v = p.getProperty(k);
                if (!placeholders(v).isEmpty()) {
                    MessageFormat.format(v, "a", "b", "c", "d", "e");     // throws on stray quotes or braces
                }
            }
        }
    }

    @Test
    void marathiIsRealMarathiNotACopyOfEnglish() throws IOException {
        Properties en = load("messages.properties");
        Properties mr = load("messages_mr.properties");
        int devanagari = 0;
        for (String k : mr.stringPropertyNames()) {
            if (mr.getProperty(k).codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097F)) {
                devanagari++;
            }
        }
        assertTrue(devanagari * 100 / mr.size() >= 90, "most Marathi messages should be in Devanagari");
        assertTrue(!en.getProperty("nav.dashboard").equals(mr.getProperty("nav.dashboard")));
    }

    @Test
    void whatsappThankYouMessageUsesAllFiveValuesInBothLanguages() throws IOException {
        for (String file : new String[]{"messages.properties", "messages_mr.properties"}) {
            String text = MessageFormat.format(load(file).getProperty("wa.message"),
                    "NAME", "SHOP", "INV-1", "1,250.00", "https://x/i/tok");
            for (String part : new String[]{"NAME", "SHOP", "INV-1", "1,250.00", "https://x/i/tok"}) {
                assertTrue(text.contains(part), file + " wa.message is missing " + part);
            }
        }
    }
}
