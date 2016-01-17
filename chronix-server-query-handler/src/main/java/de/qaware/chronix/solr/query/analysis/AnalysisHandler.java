/*
 * Copyright (C) 2016 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr.query.analysis;

import de.qaware.chronix.solr.query.ChronixQueryParams;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisDocumentBuilder;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisQueryEvaluator;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisType;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.component.SearchHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Analysis search handler
 *
 * @author f.lautenschlager
 */
public class AnalysisHandler extends SearchHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisHandler.class);

    private final DocListProvider docListProvider;

    /**
     * Constructs an isAggregation handler
     *
     * @param docListProvider - the search provider for the DocList Result
     */
    public AnalysisHandler(DocListProvider docListProvider) {
        this.docListProvider = docListProvider;
    }

    /**
     * Executes the user search request.
     *
     * @param req the solr query request
     * @param rsp the solr query response holding the result
     * @throws Exception if bad things happen
     */
    @Override
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
        LOGGER.debug("Handling analysis request {}", req);
        //First check if the request should return documents => rows > 0
        SolrParams params = req.getParams();
        String rowsParam = params.get(CommonParams.ROWS, null);
        int rows = -1;
        if (rowsParam != null) {
            rows = Integer.parseInt(rowsParam);
        }

        SolrDocumentList results = new SolrDocumentList();
        String[] filterQueries = req.getParams().getParams(CommonParams.FQ);


        //Do a query and collect them on the join function
        Map<String, List<SolrDocument>> collectedDocs = collectDocuments(req, JoinFunctionEvaluator.joinFunction(filterQueries));

        //If now rows should returned, we only return the num found
        if (rows == 0) {
            results.setNumFound(collectedDocs.keySet().size());
        } else {
            //Otherwise return the aggregated time series
            long queryStart = Long.parseLong(params.get(ChronixQueryParams.QUERY_START_LONG));
            long queryEnd = Long.parseLong(params.get(ChronixQueryParams.QUERY_END_LONG));

            //We have an analysis query
            List<SolrDocument> aggregatedDocs = analyze(collectedDocs,
                    AnalysisQueryEvaluator.buildAnalysis(filterQueries),
                    queryStart, queryEnd);

            results.addAll(aggregatedDocs);
            results.setNumFound(aggregatedDocs.size());
        }
        rsp.add("response", results);

        //avoid calling the print response method
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Sending response {}", printResponse(rsp, filterQueries));
        }

    }

    /**
     * Collects the document matching the given solr query request by using the given collection key function.
     *
     * @param req           the solr query request
     * @param collectionKey the collection key function to group documents
     * @return the collected and grouped documents
     * @throws IOException if bad things happen
     */
    private Map<String, List<SolrDocument>> collectDocuments(SolrQueryRequest req, Function<SolrDocument, String> collectionKey) throws IOException {
        String query = req.getParams().get(CommonParams.Q);
        Set<String> fields = getFields(req.getParams().get(CommonParams.FL));

        //query and collect all documents
        DocList result = docListProvider.doSimpleQuery(query, req, 0, Integer.MAX_VALUE);
        SolrDocumentList docs = docListProvider.docListToSolrDocumentList(result, req.getSearcher(), fields, null);
        return AnalysisDocumentBuilder.collect(docs, collectionKey);
    }

    /**
     * Converts the fields parameter in a set with single fields
     *
     * @param fl the fields parameter as string
     * @return a set containing the single fields split on ','
     */
    private Set<String> getFields(String fl) {
        if (fl == null) {
            return null;
        }
        String[] fields = fl.split(",");
        Set<String> returnFields = new HashSet<>();
        Collections.addAll(returnFields, fields);
        return returnFields;
    }

    /**
     * Analyzes the collected documents using the given analysis
     *
     * @param collectedDocs the collected solr documents (time series records)
     * @param analysis      the analysis that should be applied
     * @param queryStart    the start from the given query
     * @param queryEnd      the end from the given query
     * @return a list with analyzed solr documents
     */
    private List<SolrDocument> analyze(Map<String, List<SolrDocument>> collectedDocs, Map.Entry<AnalysisType, String[]> analysis, long queryStart, long queryEnd) {
        List<SolrDocument> solrDocuments = Collections.synchronizedList(new ArrayList<>(collectedDocs.size()));
        collectedDocs.entrySet().parallelStream().forEach(docs -> {
            SolrDocument doc = AnalysisDocumentBuilder.analyze(analysis, queryStart, queryEnd, docs);
            if (doc != null) {
                solrDocuments.add(doc);
            }
        });
        return solrDocuments;
    }

    /**
     * Converts the response and the filter query in a string representation.
     *
     * @param rsp           the solr query response
     * @param filterQueries the filter queries
     * @return a string representation
     */
    private String printResponse(SolrQueryResponse rsp, String[] filterQueries) {
        return rsp.getToLogAsString(String.join("-", filterQueries == null ? "" : Arrays.toString(filterQueries))) + "/";
    }

    /**
     * @return the description shown in apache solr
     */
    @Override
    public String getDescription() {
        return "Chronix Aggregation Request Handler";
    }
}
