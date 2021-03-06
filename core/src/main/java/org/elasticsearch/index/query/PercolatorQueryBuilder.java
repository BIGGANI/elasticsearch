/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.analysis.FieldNameAnalyzer;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.DocumentMapperForType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;
import org.elasticsearch.index.percolator.PercolatorFieldMapper;
import org.elasticsearch.index.percolator.PercolatorQueryCache;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.index.mapper.SourceToParse.source;

public class PercolatorQueryBuilder extends AbstractQueryBuilder<PercolatorQueryBuilder> {

    public static final String NAME = "percolator";
    public static final ParseField QUERY_NAME_FIELD = new ParseField(NAME);

    static final ParseField DOCUMENT_FIELD = new ParseField("document");
    private static final ParseField QUERY_FIELD = new ParseField("field");
    private static final ParseField DOCUMENT_TYPE_FIELD = new ParseField("document_type");
    private static final ParseField INDEXED_DOCUMENT_FIELD_INDEX = new ParseField("index");
    private static final ParseField INDEXED_DOCUMENT_FIELD_TYPE = new ParseField("type");
    private static final ParseField INDEXED_DOCUMENT_FIELD_ID = new ParseField("id");
    private static final ParseField INDEXED_DOCUMENT_FIELD_ROUTING = new ParseField("routing");
    private static final ParseField INDEXED_DOCUMENT_FIELD_PREFERENCE = new ParseField("preference");
    private static final ParseField INDEXED_DOCUMENT_FIELD_VERSION = new ParseField("version");

    private final String field;
    private final String documentType;
    private final BytesReference document;

    private final String indexedDocumentIndex;
    private final String indexedDocumentType;
    private final String indexedDocumentId;
    private final String indexedDocumentRouting;
    private final String indexedDocumentPreference;
    private final Long indexedDocumentVersion;

    public PercolatorQueryBuilder(String field, String documentType, BytesReference document) {
        if (field == null) {
            throw new IllegalArgumentException("[field] is a required argument");
        }
        if (documentType == null) {
            throw new IllegalArgumentException("[document_type] is a required argument");
        }
        if (document == null) {
            throw new IllegalArgumentException("[document] is a required argument");
        }
        this.field = field;
        this.documentType = documentType;
        this.document = document;
        indexedDocumentIndex = null;
        indexedDocumentType = null;
        indexedDocumentId = null;
        indexedDocumentRouting = null;
        indexedDocumentPreference = null;
        indexedDocumentVersion = null;
    }

    public PercolatorQueryBuilder(String field, String documentType, String indexedDocumentIndex, String indexedDocumentType,
                                  String indexedDocumentId, String indexedDocumentRouting, String indexedDocumentPreference,
                                  Long indexedDocumentVersion) {
        if (field == null) {
            throw new IllegalArgumentException("[field] is a required argument");
        }
        if (documentType == null) {
            throw new IllegalArgumentException("[document_type] is a required argument");
        }
        if (indexedDocumentIndex == null) {
            throw new IllegalArgumentException("[index] is a required argument");
        }
        if (indexedDocumentType == null) {
            throw new IllegalArgumentException("[type] is a required argument");
        }
        if (indexedDocumentId == null) {
            throw new IllegalArgumentException("[id] is a required argument");
        }
        this.field = field;
        this.documentType = documentType;
        this.indexedDocumentIndex = indexedDocumentIndex;
        this.indexedDocumentType = indexedDocumentType;
        this.indexedDocumentId = indexedDocumentId;
        this.indexedDocumentRouting = indexedDocumentRouting;
        this.indexedDocumentPreference = indexedDocumentPreference;
        this.indexedDocumentVersion = indexedDocumentVersion;
        this.document = null;
    }

    /**
     * Read from a stream.
     */
    public PercolatorQueryBuilder(StreamInput in) throws IOException {
        super(in);
        field = in.readString();
        documentType = in.readString();
        indexedDocumentIndex = in.readOptionalString();
        indexedDocumentType = in.readOptionalString();
        indexedDocumentId = in.readOptionalString();
        indexedDocumentRouting = in.readOptionalString();
        indexedDocumentPreference = in.readOptionalString();
        if (in.readBoolean()) {
            indexedDocumentVersion = in.readVLong();
        } else {
            indexedDocumentVersion = null;
        }
        document = in.readOptionalBytesReference();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(field);
        out.writeString(documentType);
        out.writeOptionalString(indexedDocumentIndex);
        out.writeOptionalString(indexedDocumentType);
        out.writeOptionalString(indexedDocumentId);
        out.writeOptionalString(indexedDocumentRouting);
        out.writeOptionalString(indexedDocumentPreference);
        if (indexedDocumentVersion != null) {
            out.writeBoolean(true);
            out.writeVLong(indexedDocumentVersion);
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalBytesReference(document);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(DOCUMENT_TYPE_FIELD.getPreferredName(), documentType);
        builder.field(QUERY_FIELD.getPreferredName(), field);
        if (document != null) {
            XContentType contentType = XContentFactory.xContentType(document);
            if (contentType == builder.contentType()) {
                builder.rawField(DOCUMENT_FIELD.getPreferredName(), document);
            } else {
                try (XContentParser parser = XContentFactory.xContent(contentType).createParser(document)) {
                    parser.nextToken();
                    builder.field(DOCUMENT_FIELD.getPreferredName());
                    builder.copyCurrentStructure(parser);
                }
            }
        }
        if (indexedDocumentIndex != null || indexedDocumentType != null || indexedDocumentId != null) {
            if (indexedDocumentIndex != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_INDEX.getPreferredName(), indexedDocumentIndex);
            }
            if (indexedDocumentType != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_TYPE.getPreferredName(), indexedDocumentType);
            }
            if (indexedDocumentId != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_ID.getPreferredName(), indexedDocumentId);
            }
            if (indexedDocumentRouting != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_ROUTING.getPreferredName(), indexedDocumentRouting);
            }
            if (indexedDocumentPreference != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_PREFERENCE.getPreferredName(), indexedDocumentPreference);
            }
            if (indexedDocumentVersion != null) {
                builder.field(INDEXED_DOCUMENT_FIELD_VERSION.getPreferredName(), indexedDocumentVersion);
            }
        }
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    public static PercolatorQueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;

        String field = null;
        String documentType = null;

        String indexedDocumentIndex = null;
        String indexedDocumentType = null;
        String indexedDocumentId = null;
        String indexedDocumentRouting = null;
        String indexedDocumentPreference = null;
        Long indexedDocumentVersion = null;

        BytesReference source = null;

        String queryName = null;
        String currentFieldName = null;

        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (parseContext.getParseFieldMatcher().match(currentFieldName, DOCUMENT_FIELD)) {
                    try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
                        builder.copyCurrentStructure(parser);
                        builder.flush();
                        source = builder.bytes();
                    }
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[" + PercolatorQueryBuilder.NAME +
                            "] query does not support [" + token + "]");
                }
            } else if (token.isValue()) {
                if (parseContext.getParseFieldMatcher().match(currentFieldName, QUERY_FIELD)) {
                    field = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, DOCUMENT_TYPE_FIELD)) {
                    documentType = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_INDEX)) {
                    indexedDocumentIndex = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_TYPE)) {
                    indexedDocumentType = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_ID)) {
                    indexedDocumentId = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_ROUTING)) {
                    indexedDocumentRouting = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_PREFERENCE)) {
                    indexedDocumentPreference = parser.text();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, INDEXED_DOCUMENT_FIELD_VERSION)) {
                    indexedDocumentVersion = parser.longValue();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, AbstractQueryBuilder.BOOST_FIELD)) {
                    boost = parser.floatValue();
                } else if (parseContext.getParseFieldMatcher().match(currentFieldName, AbstractQueryBuilder.NAME_FIELD)) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[" + PercolatorQueryBuilder.NAME +
                            "] query does not support [" + currentFieldName + "]");
                }
            } else {
                throw new ParsingException(parser.getTokenLocation(), "[" + PercolatorQueryBuilder.NAME +
                        "] query does not support [" + token + "]");
            }
        }

        if (documentType == null) {
            throw new IllegalArgumentException("[" + PercolatorQueryBuilder.NAME + "] query is missing required [" +
                    DOCUMENT_TYPE_FIELD.getPreferredName() + "] parameter");
        }

        PercolatorQueryBuilder queryBuilder;
        if (source != null) {
            queryBuilder = new PercolatorQueryBuilder(field, documentType, source);
        } else if (indexedDocumentId != null) {
            queryBuilder = new PercolatorQueryBuilder(field, documentType, indexedDocumentIndex, indexedDocumentType,
                    indexedDocumentId, indexedDocumentRouting, indexedDocumentPreference, indexedDocumentVersion);
        } else {
            throw new IllegalArgumentException("[" + PercolatorQueryBuilder.NAME + "] query, nothing to percolate");
        }
        queryBuilder.queryName(queryName);
        queryBuilder.boost(boost);
        return queryBuilder;
    }

    @Override
    protected boolean doEquals(PercolatorQueryBuilder other) {
        return Objects.equals(field, other.field)
                && Objects.equals(documentType, other.documentType)
                && Objects.equals(document, other.document)
                && Objects.equals(indexedDocumentIndex, other.indexedDocumentIndex)
                && Objects.equals(indexedDocumentType, other.indexedDocumentType)
                && Objects.equals(indexedDocumentId, other.indexedDocumentId);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(field, documentType, document, indexedDocumentIndex, indexedDocumentType, indexedDocumentId);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected QueryBuilder<?> doRewrite(QueryRewriteContext queryShardContext) throws IOException {
        if (document != null) {
            return this;
        }

        GetRequest getRequest = new GetRequest(indexedDocumentIndex, indexedDocumentType, indexedDocumentId);
        getRequest.preference("_local");
        getRequest.routing(indexedDocumentRouting);
        getRequest.preference(indexedDocumentPreference);
        if (indexedDocumentVersion != null) {
            getRequest.version(indexedDocumentVersion);
        }
        GetResponse getResponse = queryShardContext.getClient().get(getRequest).actionGet();
        if (getResponse.isExists() == false) {
            throw new ResourceNotFoundException(
                    "indexed document [{}/{}/{}] couldn't be found", indexedDocumentIndex, indexedDocumentType, indexedDocumentId
            );
        }
        return new PercolatorQueryBuilder(field, documentType, getResponse.getSourceAsBytesRef());
    }

    @Override
    protected Query doToQuery(QueryShardContext context) throws IOException {
        if (indexedDocumentIndex != null || indexedDocumentType != null || indexedDocumentId != null) {
            throw new IllegalStateException("query builder must be rewritten first");
        }

        if (document == null) {
            throw new IllegalStateException("nothing to percolator");
        }

        MapperService mapperService = context.getMapperService();
        DocumentMapperForType docMapperForType = mapperService.documentMapperWithAutoCreate(documentType);
        DocumentMapper docMapper = docMapperForType.getDocumentMapper();

        ParsedDocument doc = docMapper.parse(source(document)
            .index(context.index().getName())
            .id("_temp_id")
            .type(documentType));

        FieldNameAnalyzer fieldNameAnalyzer = (FieldNameAnalyzer) docMapper.mappers().indexAnalyzer();
        // Need to this custom impl because FieldNameAnalyzer is strict and the percolator sometimes isn't when
        // 'index.percolator.map_unmapped_fields_as_string' is enabled:
        Analyzer analyzer = new DelegatingAnalyzerWrapper(Analyzer.PER_FIELD_REUSE_STRATEGY) {
            @Override
            protected Analyzer getWrappedAnalyzer(String fieldName) {
                Analyzer analyzer = fieldNameAnalyzer.analyzers().get(fieldName);
                if (analyzer != null) {
                    return analyzer;
                } else {
                    return context.getAnalysisService().defaultIndexAnalyzer();
                }
            }
        };
        final IndexSearcher docSearcher;
        if (doc.docs().size() > 1) {
            assert docMapper.hasNestedObjects();
            docSearcher = createMultiDocumentSearcher(analyzer, doc);
        } else {
            MemoryIndex memoryIndex = MemoryIndex.fromDocument(doc.rootDoc(), analyzer, true, false);
            docSearcher = memoryIndex.createSearcher();
            docSearcher.setQueryCache(null);
        }

        PercolatorQueryCache registry = context.getPercolatorQueryCache();
        if (registry == null) {
            throw new QueryShardException(context, "no percolator query registry");
        }

        PercolatorQuery.Builder builder = new PercolatorQuery.Builder(
                documentType, registry, document, docSearcher
        );
        Settings indexSettings = registry.getIndexSettings().getSettings();
        if (indexSettings.getAsVersion(IndexMetaData.SETTING_VERSION_CREATED, null).onOrAfter(Version.V_5_0_0_alpha1)) {
            MappedFieldType fieldType = context.fieldMapper(field);
            if (fieldType == null) {
                throw new QueryShardException(context, "field [" + field + "] does not exist");
            }

            if (!(fieldType instanceof PercolatorFieldMapper.PercolatorFieldType)) {
                throw new QueryShardException(context, "expected field [" + field +
                        "] to be of type [percolator], but is of type [" + fieldType.typeName() + "]");
            }
            PercolatorFieldMapper.PercolatorFieldType pft = (PercolatorFieldMapper.PercolatorFieldType) fieldType;
            builder.extractQueryTermsQuery(pft.getExtractedTermsField(), pft.getUnknownQueryFieldName());
        } else {
            Query percolateTypeQuery = new TermQuery(new Term(TypeFieldMapper.NAME, PercolatorFieldMapper.LEGACY_TYPE_NAME));
            builder.setPercolateTypeQuery(percolateTypeQuery);
        }
        return builder.build();
    }

    public String getField() {
        return field;
    }

    public String getDocumentType() {
        return documentType;
    }

    public BytesReference getDocument() {
        return document;
    }

    private IndexSearcher createMultiDocumentSearcher(Analyzer analyzer, ParsedDocument doc) {
        IndexReader[] memoryIndices = new IndexReader[doc.docs().size()];
        List<ParseContext.Document> docs = doc.docs();
        int rootDocIndex = docs.size() - 1;
        assert rootDocIndex > 0;
        for (int i = 0; i < docs.size(); i++) {
            ParseContext.Document d = docs.get(i);
            MemoryIndex memoryIndex = MemoryIndex.fromDocument(d, analyzer, true, false);
            memoryIndices[i] = memoryIndex.createSearcher().getIndexReader();
        }
        try {
            MultiReader mReader = new MultiReader(memoryIndices, true);
            LeafReader slowReader = SlowCompositeReaderWrapper.wrap(mReader);
            final IndexSearcher slowSearcher = new IndexSearcher(slowReader) {

                @Override
                public Weight createNormalizedWeight(Query query, boolean needsScores) throws IOException {
                    BooleanQuery.Builder bq = new BooleanQuery.Builder();
                    bq.add(query, BooleanClause.Occur.MUST);
                    bq.add(Queries.newNestedFilter(), BooleanClause.Occur.MUST_NOT);
                    return super.createNormalizedWeight(bq.build(), needsScores);
                }

            };
            slowSearcher.setQueryCache(null);
            return slowSearcher;
        } catch (IOException e) {
            throw new ElasticsearchException("Failed to create index for percolator with nested document ", e);
        }
    }

}
