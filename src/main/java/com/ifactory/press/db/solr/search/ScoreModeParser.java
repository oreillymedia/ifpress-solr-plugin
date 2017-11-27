package com.ifactory.press.db.solr.search;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.solr.search.SyntaxError;

class ScoreModeParser {

    final private static Map<String, ScoreMode> lowerAndCapitalCase
            = Collections.unmodifiableMap(new HashMap<String, ScoreMode>() {
                {
                    for (ScoreMode s : ScoreMode.values()) {
                        put(s.name().toLowerCase(Locale.ROOT), s);
                        put(s.name(), s);
                    }
                }
            });

    private ScoreModeParser() {
    }

    /**
     * recognizes as-is {@link ScoreMode} names, and lowercase as well,
     * otherwise throws exception
     *
     * @throws SyntaxError when it's unable to parse
     *
     */
    static ScoreMode parse(String score) throws SyntaxError {
        final ScoreMode scoreMode = lowerAndCapitalCase.get(score);
        if (scoreMode == null) {
            throw new SyntaxError("Unable to parse ScoreMode from: " + score);
        }
        return scoreMode;
    }

}
