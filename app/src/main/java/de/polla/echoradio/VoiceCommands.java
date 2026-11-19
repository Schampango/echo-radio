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

    /** Die Woerter, auf die reagiert wird (in der Weboberflaeche aenderbar). */
    static final class Words {
        final List<String> wake;
        final List<String> stop;
        final List<String> louder;
        final List<String> quieter;
        final List<String> volume;
        final Set<String> fillers;

        Words(List<String> wake, List<String> stop, List<String> louder, List<String> quieter,
              List<String> volume, List<String> fillers) {
            this.wake = clean(wake, DEFAULT_WAKE);
            this.stop = clean(stop, DEFAULT_STOP);
            this.louder = clean(louder, DEFAULT_LOUDER);
            this.quieter = clean(quieter, DEFAULT_QUIETER);
            this.volume = clean(volume, DEFAULT_VOLUME);
            // Fuellwoerter duerfen leer sein
            Set<String> f = new HashSet<>();
            for (String w : fillers) {
                f.addAll(tokens(w));
            }
            this.fillers = f;
        }

        static Words defaults() {
            return new Words(DEFAULT_WAKE, DEFAULT_STOP, DEFAULT_LOUDER, DEFAULT_QUIETER,
                    DEFAULT_VOLUME, DEFAULT_FILLERS);
        }

        /** Kleinbuchstaben, ohne Satzzeichen, ohne Doppelte; leer -> Standard. */
        private static List<String> clean(List<String> in, List<String> fallback) {
            List<String> out = new ArrayList<>();
            for (String w : in) {
                String t = String.join(" ", tokens(w));
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
            return out.isEmpty() ? new ArrayList<>(fallback) : out;
        }
    }

    static final List<String> DEFAULT_WAKE = Arrays.asList("alexa");
    static final List<String> DEFAULT_STOP = Arrays.asList(
            "stopp", "stop", "stoppe", "stoppen", "aus", "pause", "ruhe", "halt", "beenden", "beende");
    static final List<String> DEFAULT_LOUDER = Arrays.asList("lauter");
    static final List<String> DEFAULT_QUIETER = Arrays.asList("leiser");
    static final List<String> DEFAULT_VOLUME = Arrays.asList("lautstärke");
    static final List<String> DEFAULT_FILLERS = Arrays.asList(
            "bitte", "mal", "doch", "jetzt", "spiel", "spiele", "spielt", "spieler", "starte", "start",
            "mach", "mache", "öffne", "oeffne", "hör", "höre", "hoer", "hoere", "den", "die", "das",
            "dem", "der", "sender", "auf", "an");

    /** Aktuelle Woerter; wird beim Speichern ersetzt. */
    static volatile Words words = Words.defaults();

    private static final double WAKE_SIMILARITY = 0.75;
    private static final double WORD_SIMILARITY = 0.8;
    private static final double MATCH_THRESHOLD = 0.62;
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
        Words w = words;
        while (!rest.isEmpty() && w.fillers.contains(rest.get(0))) {
            rest.remove(0);
        }
        String restText = String.join(" ", rest);
        if (rest.isEmpty()) {
            return new Command(wakeHeard ? Kind.WAKE_ONLY : Kind.NONE, null, 0, wakeHeard, "");
        }

        // Lautstaerke 1..10
        int volumeLen = startsWithPhrase(rest, w.volume, 0.7);
        if (volumeLen > 0) {
            for (int i = volumeLen; i < rest.size(); i++) {
                Integer n = NUMBERS.get(rest.get(i));
                if (n == null) {
                    n = parseIntOrNull(rest.get(i));
                }
                if (n != null && n >= 1 && n <= 10) {
                    return new Command(Kind.SET_VOLUME, null, n, wakeHeard, restText);
                }
            }
        }
        if (rest.size() <= 4) {
            if (containsPhrase(rest, w.stop, WORD_SIMILARITY)) {
                return new Command(Kind.STOP, null, 0, wakeHeard, restText);
            }
            if (containsPhrase(rest, w.louder, WORD_SIMILARITY)) {
                return new Command(Kind.VOLUME_UP, null, 0, wakeHeard, restText);
            }
            if (containsPhrase(rest, w.quieter, WORD_SIMILARITY)) {
                return new Command(Kind.VOLUME_DOWN, null, 0, wakeHeard, restText);
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
        List<String> wakeWords = words.wake;
        for (int i = 0; i < tokens.size(); i++) {
            for (String wake : wakeWords) {
                String target = wake.replace(" ", "");
                int n = wake.split(" ").length;
                // Gesagtes ueber n Woerter zusammengefasst
                if (i + n <= tokens.size()) {
                    double single = similarity(String.join("", tokens.subList(i, i + n)), target);
                    // Auch ein Wort mehr pruefen ("alex a" -> "alexa")
                    if (i + n < tokens.size()) {
                        double pair = similarity(String.join("", tokens.subList(i, i + n + 1)), target);
                        if (pair >= 0.8 && pair > single) {
                            return new int[]{i, n + 1};
                        }
                    }
                    if (single >= WAKE_SIMILARITY) {
                        return new int[]{i, n};
                    }
                }
            }
        }
        return new int[]{-1, 0};
    }

    /** Kommt einer der Begriffe (auch aus mehreren Woertern) irgendwo im Gesagten vor? */
    static boolean containsPhrase(List<String> said, List<String> phrases, double threshold) {
        for (String phrase : phrases) {
            List<String> p = tokens(phrase);
            if (p.isEmpty()) {
                continue;
            }
            String target = String.join(" ", p);
            for (int i = 0; i + p.size() <= said.size(); i++) {
                String window = String.join(" ", said.subList(i, i + p.size()));
                // Kurze Woerter ("aus") muessen genau passen
                double needed = target.length() <= 4 ? 1.0 : threshold;
                if (similarity(window, target) >= needed) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Laenge des Begriffs, mit dem das Gesagte beginnt, oder 0. */
    private static int startsWithPhrase(List<String> said, List<String> phrases, double threshold) {
        for (String phrase : phrases) {
            List<String> p = tokens(phrase);
            if (p.isEmpty() || p.size() > said.size()) {
                continue;
            }
            if (similarity(String.join(" ", said.subList(0, p.size())), String.join(" ", p)) >= threshold) {
                return p.size();
            }
        }
        return 0;
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
