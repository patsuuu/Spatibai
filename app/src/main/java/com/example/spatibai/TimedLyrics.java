package com.example.spatibai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimedLyrics {
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    private static final int DURATION_TOLERANCE_SECONDS = 5;

    private TimedLyrics() {}

    static ArrayList<Line> parse(String syncedLyrics) {
        ArrayList<Line> lines = new ArrayList<>();
        if (syncedLyrics == null || syncedLyrics.isEmpty()) return lines;
        for (String sourceLine : syncedLyrics.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(sourceLine);
            ArrayList<Integer> timestamps = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int previousEnd = 0;
            while (matcher.find()) {
                text.append(sourceLine, previousEnd, matcher.start());
                previousEnd = matcher.end();
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                String fraction = matcher.group(3);
                int milliseconds = 0;
                if (fraction != null) {
                    milliseconds = Integer.parseInt(fraction);
                    if (fraction.length() == 1) milliseconds *= 100;
                    else if (fraction.length() == 2) milliseconds *= 10;
                }
                timestamps.add((minutes * 60 + seconds) * 1000 + milliseconds);
            }
            text.append(sourceLine.substring(previousEnd));
            String lyricText = text.toString().trim();
            if (!lyricText.isEmpty()) {
                for (int timestamp : timestamps) {
                    lines.add(new Line(timestamp, lyricText));
                }
            }
        }
        Collections.sort(lines, Comparator.comparingInt(line -> line.timeMs));
        return lines;
    }

    static boolean matchesDuration(int expectedSeconds, double actualSeconds) {
        return expectedSeconds <= 0 ||
                Double.isFinite(actualSeconds) &&
                        Math.abs(expectedSeconds - actualSeconds) <= DURATION_TOLERANCE_SECONDS;
    }

    static final class Line {
        final int timeMs;
        final String text;

        Line(int timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
