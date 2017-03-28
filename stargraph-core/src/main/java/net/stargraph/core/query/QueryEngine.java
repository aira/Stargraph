package net.stargraph.core.query;

import net.stargraph.Language;
import net.stargraph.StarGraphException;
import net.stargraph.core.Stargraph;
import net.stargraph.core.query.nli.*;
import net.stargraph.core.search.EntitySearcher;
import net.stargraph.model.InstanceEntity;
import net.stargraph.rank.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.List;
import java.util.Objects;

public final class QueryEngine {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private Marker marker = MarkerFactory.getMarker("query");

    private String dbId;
    private Stargraph core;
    private Analyzers analyzers;

    public QueryEngine(String dbId, Stargraph core) {
        this.dbId = Objects.requireNonNull(dbId);
        this.core = Objects.requireNonNull(core);
        this.analyzers = new Analyzers(core.getConfig());
    }

    public AnswerSet nliQuery(String userQuery, Language language) {
        QuestionAnalyzer analyzer = this.analyzers.getQuestionAnalyzer(language);
        QuestionAnalysis analysis = analyzer.analyse(userQuery);
        SPARQLQueryBuilder queryBuilder = analysis.getSPARQLQueryBuilder();

        QueryPlanPatterns triplePatterns = queryBuilder.getTriplePatterns();
        List<DataModelBinding> bindings = queryBuilder.getBindings();

        triplePatterns.forEach(triplePattern -> {
            logger.debug(marker, "Resolving {}", triplePattern);
            resolve(asTriple(triplePattern, bindings), queryBuilder);
        });

        queryBuilder.build();
        System.out.println(queryBuilder);
        return null;
    }


    private void resolve(Triple triple, SPARQLQueryBuilder builder) {
        InstanceEntity pivot = resolvePivot(triple.s, builder);
        pivot = pivot != null ? pivot : resolvePivot(triple.o, builder);
        resolvePredicate(pivot, triple.p, builder);
    }

    private void resolvePredicate(InstanceEntity pivot, DataModelBinding binding, SPARQLQueryBuilder builder) {
        if ((binding.getModelType() == DataModelType.CLASS
                || binding.getModelType() == DataModelType.PROPERTY) && !builder.isResolved(binding)) {

            EntitySearcher searcher = core.createEntitySearcher();
            ModifiableSearchParams searchParams = ModifiableSearchParams.create(dbId).term(binding.getTerm());
            ModifiableRankParams rankParams = ParamsBuilder.word2vec();
            Scores scores = searcher.pivotedSearch(pivot, searchParams, rankParams);
            builder.add(binding, (Rankable) scores.get(0).getEntry());
        }
    }

    private InstanceEntity resolvePivot(DataModelBinding binding, SPARQLQueryBuilder builder) {
        List<Rankable> solutions = builder.getSolutions(binding);
        if (solutions != null) {
            return (InstanceEntity)solutions.get(0);
        }

        if (binding.getModelType() == DataModelType.INSTANCE) {
            EntitySearcher searcher = core.createEntitySearcher();
            ModifiableSearchParams searchParams = ModifiableSearchParams.create(dbId).term(binding.getTerm());
            ModifiableRankParams rankParams = ParamsBuilder.levenshtein(); // threshold defaults to auto
            Scores scores = searcher.instanceSearch(searchParams, rankParams);
            InstanceEntity instance = (InstanceEntity) scores.get(0).getEntry();
            builder.add(binding, instance);
            return instance;
        }
        return null;
    }

    private Triple asTriple(TriplePattern pattern, List<DataModelBinding> bindings) {
        String[] components = pattern.getPattern().split("\\s");
        return new Triple(map(components[0], bindings), map(components[1], bindings), map(components[2], bindings));
    }

    private DataModelBinding map(String placeHolder, List<DataModelBinding> bindings) {
        if (placeHolder.startsWith("?VAR") || placeHolder.startsWith("TYPE")) {
            DataModelType type = placeHolder.startsWith("?VAR") ? DataModelType.VARIABLE : DataModelType.TYPE;
            return new DataModelBinding(type, placeHolder, placeHolder);
        }

        return bindings.stream()
                .filter(b -> b.getPlaceHolder().equals(placeHolder))
                .findAny().orElseThrow(() -> new StarGraphException("Unmapped placeholder '" + placeHolder + "'"));
    }

    private static class Triple {
        Triple(DataModelBinding s, DataModelBinding p, DataModelBinding o) {
            this.s = s;
            this.p = p;
            this.o = o;
        }

        public DataModelBinding s;
        public DataModelBinding p;
        public DataModelBinding o;

        @Override
        public String toString() {
            return "Triple{" +
                    "s=" + s +
                    ", p=" + p +
                    ", o=" + o +
                    '}';
        }
    }
}