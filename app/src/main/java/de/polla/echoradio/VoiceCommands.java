package de.polla.echoradio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wertet erkannten Text aus ("alexa spiel deutschlandfunk") und macht daraus einen Befehl.
 * Vergleicht unscharf, weil das kleine Sprachmodell Namen wie "Brillux" nicht kennt
 * und sie z. B. als "brille" erkennt. Ohne Android-Abhaengigkeiten, damit es testbar ist.
 */
final class VoiceCommands {

    enum Kind { NONE, WAKE_ONLY, PLAY, STOP, VOLUME_UP, VOLUME_DOWN, SET_VOLUME }

    /** Etwas, das per Namen gestartet werden kann (ein Knopf). */
    static final class Target {
        final String id;
        final List<String> names;

        Target(String id, List<String> names) {
            this.id = id;
            this.names = names;
        }
    }

    static final class Command {
        final Kind kind;
        final Target target;
        final int level;
        /** Ob das Aktivierungswort in diesem Text vorkam. */
        final boolean wakeHeard;
        /** Der Teil nach dem Aktivierungswort. */
        final String rest;

        Command(Kind kind, Target target, int level, boolean wakeHeard, String rest) {
            this.kind = kind;
            this.target = target;
            this.level = level;
            this.wakeHeard = wakeHeard;
            this.rest = rest;
        }

        @Override
        public String toString() {
            return kind + (target != null ? "(" + target.id + ")" : "")
                    + (kind == Kind.SET_VOLUME ? "(" + level + ")" : "");
        }
    }

    static final String WAKE_WORD = "alexa";
    private static final double WAKE_SIMILARITY = 0.75;
    private static final double MATCH_THRESHOLD = 0.62;

    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList(
            "bitte", "mal", "doch", "jetzt", "spiel", "spiele", "spielt", "spieler", "starte", "start",
            "mach", "mache", "öffne", "oeffne", "hör", "höre", "hoer", "hoere", "den", "die", "das",
            "dem", "der", "sender", "auf", "an"));
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "stopp", "stop", "stoppe", "stoppen", "aus", "pause", "ruhe", "halt", "beenden", "beende"));
    private static final Map<String, Integer> NUMBERS = new HashMap<>();

    static {
        String[][] words = {
                {"null", "0"}, {"eins", "1"}, {"ein", "1"}, {"eine", "1"}, {"einen", "1"}, {"zwei", "2"},
                {"zwo", "2"}, {"drei", "3"}, {"vier", "4"}, {"fünf", "5"}, {"fuenf", "5"}, {"sechs", "6"},
                {"sieben", "7"}, {"acht", "8"}, {"neun", "9"}, {"zehn", "10"},
        };
        for (String[] w : words) {
            NUMBERS.put(w[0], Integer.parseInt(w[1]));
        }
    }

    private static final String[] DIGIT_WORDS = {
            "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn"};

    private VoiceCommands() {
    }

    /** Ob der (Teil-)Text das Aktivierungswort enthaelt. */
    static boolean hasWakeWord(String text) {
        return findWake(tokens(text))[0] >= 0;
    }

    /**
     * @param awaiting true, wenn das Aktivierungswort gerade eben schon allein gesagt wurde;
     *                 dann zaehlt der Text auch ohne "Alexa" als Befehl.
     */
    static Command parse(String text, List<Target> targets, boolean awaiting) {
        List<String> tokens = tokens(text);
        int[] wake = findWake(tokens);
        boolean wakeHeard = wake[0] >= 0;
        if (!wakeHeard && !awaiting) {
            return new Command(Kind.NONE, null, 0, false, "");
        }
        List<String> rest = new ArrayList<>(wakeHeard ? tokens.subList(wake[0] + wake[1], tokens.size()) : tokens);
        while (!rest.isEmpty() && FILLERS.contains(rest.get(0))) {
            rest.remove(0);
        }
        String restText = String.join(" ", rest);
        if (rest.isEmpty()) {
            return new Command(wakeHeard ? Kind.WAKE_ONLY : Kind.NONE, null, 0, wakeHeard, "");
        }

        // Lautstaerke 1..10
        if (similarity(rest.get(0), "lautstärke") >= 0.7) {
            for (int i = 1; i < rest.size(); i++) {
                Integer n = NUMBERS.get(rest.get(i));
                if (n == null) {
                    n = parseIntOrNull(rest.get(i));
                }
                if (n != null && n >= 1 && n <= 10) {
                    return new Command(Kind.SET_VOLUME, null, n, wakeHeard, restText);
                }
            }
        }
        if (rest.size() <= 3) {
            for (String t : rest) {
                if (STOP_WORDS.contains(t)) {
                    return new Command(Kind.STOP, null, 0, wakeHeard, restText);
                }
                if (similarity(t, "lauter") >= 0.8) {
                    return new Command(Kind.VOLUME_UP, null, 0, wakeHeard, restText);
                }
                if (similarity(t, "leiser") >= 0.8) {
                    return new Command(Kind.VOLUME_DOWN, null, 0, wakeHeard, restText);
                }
            }
        }

        // Knopf per Name
        Target best = null;
        double bestScore = 0;
        for (Target target : targets) {
            for (String name : target.names) {
                double score = matchScore(rest, name);
                if (score > bestScore) {
                    bestScore = score;
                    best = target;
                }
            }
        }
        if (best != null && bestScore >= MATCH_THRESHOLD) {
            return new Command(Kind.PLAY, best, 0, wakeHeard, restText);
        }
        return new Command(Kind.NONE, null, 0, wakeHeard, restText);
    }

    /** Wie gut passt das Gesagte zu einem Namen (0..1). */
    static double matchScore(List<String> spoken, String name) {
        List<String> nameTokens = tokens(name);
        if (nameTokens.isEmpty()) {
            return 0;
        }
        // "radio" ist bei Namen oft nur Beiwerk
        List<String> said = new ArrayList<>(spoken);
        if (said.size() > 1 && said.get(0).equals("radio")) {
            said.remove(0);
        }
        String a = String.join(" ", said);
        String b = String.join(" ", nameTokens);
        double score = Math.max(similarity(a, b), similarity(a.replace(" ", ""), b.replace(" ", "")));

        // Alle gesagten Woerter kommen im Namen vor ("bundesliga" -> "Bundesliga Konferenz")
        boolean allFound = true;
        for (String w : said) {
            boolean found = false;
            for (String n : nameTokens) {
                if (w.length() >= 3 && similarity(w, n) >= 0.8) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                allFound = false;
                break;
            }
        }
        if (allFound) {
            score = Math.max(score, 0.85);
        }
        return score;
    }

    /** Kleinbuchstaben, nur Buchstaben/Ziffern, Zahlen 0..10 als Woerter. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String s = text.toLowerCase(Locale.GERMAN).replaceAll("[^a-z0-9äöüß]+", " ").trim();
        if (s.isEmpty()) {
            return out;
        }
        for (String t : s.split(" ")) {
            if (t.matches("\\d+")) {
                int n = Integer.parseInt(t);
                if (n >= 0 && n <= 10) {
                    t = DIGIT_WORDS[n];
                }
            }
            out.add(t);
        }
        return out;
    }

    /** {Position, Anzahl Woerter} des Aktivierungsworts, oder {-1, 0}. */
    private static int[] findWake(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            double single = similarity(tokens.get(i), WAKE_WORD);
            // Zuerst zwei Woerter zusammen pruefen ("alex a" -> "alexa")
            if (i + 1 < tokens.size()) {
                double pair = similarity(tokens.get(i) + tokens.get(i + 1), WAKE_WORD);
                if (pair >= 0.8 && pair > single) {
                    return new int[]{i, 2};
                }
            }
            if (single >= WAKE_SIMILARITY) {
                return new int[]{i, 1};
            }
        }
        return new int[]{-1, 0};
    }

    /** 1 - Levenshtein-Abstand / Laenge des laengeren Worts. */
    static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
