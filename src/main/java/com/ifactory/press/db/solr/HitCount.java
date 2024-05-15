/*
 * Copyright 2014 Safari Books Online
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ifactory.press.db.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.valuesource.DoubleConstValueSource;
import org.apache.lucene.queries.function.valuesource.SumFloatFunction;
import org.apache.lucene.queries.function.valuesource.TermFreqValueSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.solr.search.FunctionQParser;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.ValueSourceParser;

/**
 * Defines the Solr function hitcount([field, ...]) which returns the total
 * of termfreq(term) for all terms in the query.  The arguments specify
 * fields whose terms are to be counted.  If no arguments are passed, terms
 * from every field are counted.
 */
public class HitCount extends ValueSourceParser {
    
    @Override
    public ValueSource parse(FunctionQParser fp) throws SyntaxError {
        // hitcount() takes no arguments.  If we wanted to pass a query
        // we could call fp.parseNestedQuery()
        Set<String> fields = new HashSet<String>();
        while (fp.hasMoreArguments()) {
            fields.add(fp.parseArg());
        }
        Query q = fp.subQuery(fp.getParams().get("q"), "lucene").getQuery();
        Set<Term> terms = new HashSet<Term>();
        try {
            /*
                Lucene 5.1.0 -> 5.2.0 replaced Query.extractTerms with Weight.extractTerms
                to enforce the requirement that Query.rewrite is supposed to be ran before calling this method.
                This change's author suggested the following code for Query obj not relying on a specific IndexReader:
                https://issues.apache.org/jira/browse/LUCENE-6425
             */
            IndexReader emptyReader = new MultiReader();
            //new IndexSearcher(emptyReader).createNormalizedWeight(q, true).extractTerms(terms);
        } catch (UnsupportedOperationException e) {
            return new DoubleConstValueSource (1);
        } catch (IOException e) {
            return new DoubleConstValueSource (1);
        }
        List<ValueSource> termcounts = new ArrayList<ValueSource>(terms.size());
        for (Term t : terms) {
            if (fields.isEmpty() || fields.contains (t.field())) {
                termcounts.add (new TermFreqValueSource(t.field(), t.text(), t.field(), t.bytes()));
            }
        }
        return new SumFloatFunction(termcounts.toArray(new ValueSource[termcounts.size()]));
    }
    
}
