package eu.fbk.fm.alignments.index;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import eu.fbk.fm.alignments.DBpediaResource;
import eu.fbk.fm.alignments.evaluation.DatasetEntry;
import eu.fbk.fm.alignments.index.db.tables.UserIndex;
import eu.fbk.fm.alignments.persistence.sparql.Endpoint;
import eu.fbk.fm.alignments.persistence.sparql.ResourceEndpoint;
import eu.fbk.fm.alignments.query.QueryAssemblyStrategy;
import eu.fbk.fm.alignments.query.index.AllNamesStrategy;
import eu.fbk.fm.alignments.scorer.FullyResolvedEntry;
import eu.fbk.fm.alignments.scorer.UserData;
import eu.fbk.fm.alignments.utils.DBUtils;
import eu.fbk.utils.core.CommandLine;
import org.jooq.Record2;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twitter4j.TwitterException;
import twitter4j.TwitterObjectFactory;
import twitter4j.User;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static eu.fbk.fm.alignments.PrepareTrainingSet.CANDIDATES_THRESHOLD;
import static eu.fbk.fm.alignments.index.db.tables.UserIndex.USER_INDEX;
import static eu.fbk.fm.alignments.index.db.tables.UserObjects.USER_OBJECTS;
import static org.jooq.impl.DSL.atan;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;

/**
 * Use database to fill the list of candidates for FullyResolvedEntry
 */
public class FillFromIndex implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FillFromIndex.class);
    private static final Gson GSON = new Gson();
    private static boolean exceptionPrinted = false;
    private static int noCandidates = 0;

    private final DataSource source;
    private final QueryAssemblyStrategy qaStrategy;
    private final ResourceEndpoint endpoint;

    private int timeout = 30;
    private boolean verbose = true;

    private Stopwatch watch = Stopwatch.createUnstarted();
    private AtomicInteger processed = new AtomicInteger(0);
    private AtomicInteger slow = new AtomicInteger(0);
    private AtomicInteger errors = new AtomicInteger(0);

    public FillFromIndex(ResourceEndpoint endpoint, QueryAssemblyStrategy qaStrategy, DataSource source) {
        this.qaStrategy = qaStrategy;
        this.endpoint = endpoint;
        this.source = source;
    }

    public FillFromIndex(ResourceEndpoint endpoint, QueryAssemblyStrategy qaStrategy, String connString, String connUser, String connPassword) throws IOException {
        this(endpoint, qaStrategy, DBUtils.createPGDataSource(connString, connUser, connPassword));
    }

    private static String logAppendix(FullyResolvedEntry entry, String query, int attempt) {
        String correct = "unknown";
        if (entry.entry.twitterId != null) {
            correct = entry.entry.twitterId;
        }

        return String.format("[Query: %s Entity: %s Correct: %s Attempt: %d]", query, entry.entry.resourceId, correct, attempt);
    }

    private static String logAppendix(String resourceId, String query, int attempt) {
        return String.format("[Query: %s Entity: %s Attempt: %d]", query, resourceId, attempt);
    }

    public Table<Record2<Long, BigDecimal>> constructQuery(String query) {
        return select(USER_INDEX.UID, sum(USER_INDEX.FREQ).as("freq"))
                .from(USER_INDEX)
                    .where(
                            "to_tsquery({0}) @@ to_tsvector('english_fullname', USER_INDEX.FULLNAME)",
                            query
                    )
                    .groupBy(USER_INDEX.UID)
                    .orderBy(sum(USER_INDEX.FREQ).desc())
                .limit(1000).asTable("a");
    }

    public List<UserData> queryCandidates(DBpediaResource resource) {
        List<UserData> result = new LinkedList<>();
        int attempt = 0;
        String oldQuery = null;
        String query = qaStrategy.getQuery(resource, attempt);
        while ((oldQuery == null || result.size() > 300) && !query.equals(oldQuery) && query.length() > 3) {
            result.clear();
            Stopwatch watch = Stopwatch.createStarted();
            try {
                Table<Record2<Long, BigDecimal>> subquery = constructQuery(query);
                UserIndex indexAlias = USER_INDEX.as("a");

                DSL.using(source, SQLDialect.POSTGRES)
                    .select(USER_OBJECTS.OBJECT, subquery.field("freq"))
                    .from(subquery)
                    .leftJoin(USER_OBJECTS)
                    .on(indexAlias.UID.eq(USER_OBJECTS.UID))
                    .queryTimeout(timeout)
                    .stream()
                    .forEach(record -> {
                        Object rawObject = record.get(USER_OBJECTS.OBJECT);

                        if (rawObject == null) {
                            return;
                        }
                        try {
                            UserData userData = new UserData(TwitterObjectFactory.createUser(rawObject.toString()));
                            userData.submitData("frequency", record.get(subquery.field("freq")));
                            result.add(userData);
                        } catch (TwitterException e) {
                            LOGGER.error("Error while deserializing user object", e);
                        }
                    });
                watch.stop();
            } catch (Exception e) {
                LOGGER.error("Error while requesting candidates. "+logAppendix(resource.getIdentifier(), query, attempt));
                if (!exceptionPrinted) {
                    exceptionPrinted = true;
                    e.printStackTrace();
                }
            }
            long elapsed = watch.elapsed(TimeUnit.SECONDS);
            if (verbose && elapsed > 10) {
                LOGGER.info("Slow ("+elapsed+"s) query. "+logAppendix(resource.getIdentifier(), query, attempt));
            }
            if (verbose && result.size() == 0 && noCandidates < 100) {
                noCandidates++;
                LOGGER.warn("No candidates. "+logAppendix(resource.getIdentifier(), query, attempt));
            }

            attempt++;
            oldQuery = query;
            query = qaStrategy.getQuery(resource, attempt);
        }

        if (result.size() > CANDIDATES_THRESHOLD) {
            return new LinkedList<>(result.subList(0, CANDIDATES_THRESHOLD));
        }
        return result;
    }

    /**
     * @param entry
     */
    public void fill(FullyResolvedEntry entry) {
        entry.resource = endpoint.getResourceById(entry.entry.resourceId);
        entry.candidates = queryCandidates(entry.resource);
    }

    private synchronized void initWatch() {
        if (!watch.isRunning()) {
            synchronized (this) {
                if (!watch.isRunning()) {
                    watch.start();
                }
            }
        }
    }

    private void checkWatch() {
        if (watch.elapsed(TimeUnit.SECONDS) > 120) {
            synchronized (this) {
                if (watch.elapsed(TimeUnit.SECONDS) > 120) {
                    LOGGER.info(String.format(
                            "Processed %d entities (%.2f ent/s). Errors: %d. Slow queries: %d",
                            processed.get(),
                            (float) processed.get()/120,
                            errors.get(),
                            slow.get()
                    ));
                    watch.reset().start();
                }
            }
        }
    }

    public List<Long> getUids(String resourceId) {
        initWatch();
        List<Long> candidates = new LinkedList<>();
        DBpediaResource resource = endpoint.getResourceById(resourceId);

        String query = qaStrategy.getQuery(resource);
        if (query.length() < 4) {
            LOGGER.error("Query is less than 4 symbols. Ignoring. "+logAppendix(resourceId, query, -1));
            return candidates;
        }

        Stopwatch watch = Stopwatch.createStarted();
        try {
            DSL.using(source, SQLDialect.POSTGRES)
                .select(USER_INDEX.UID)
                .from(USER_INDEX)
                .where(
                        "to_tsquery({0}) @@ to_tsvector('english_fullname', USER_INDEX.FULLNAME)",
                        qaStrategy.getQuery(resource)
                )
                .orderBy(USER_INDEX.FREQ.desc())
                .limit(CANDIDATES_THRESHOLD)
                .queryTimeout(timeout)
                .stream()
                .forEach(record -> {
                    long candidate = record.get(USER_INDEX.UID);
                    if (!candidates.contains(candidate)) {
                        candidates.add(candidate);
                    }
                });
            watch.stop();
        } catch (Exception e) {
            if (verbose) {
                LOGGER.error("Error while requesting candidates. "+logAppendix(resourceId, query, -1));
                if (!exceptionPrinted) {
                    exceptionPrinted = true;
                    e.printStackTrace();
                }
            }
            errors.incrementAndGet();
        }
        long elapsed = watch.elapsed(TimeUnit.SECONDS);
        if (elapsed > 10) {
            slow.incrementAndGet();
            if (verbose) {
                LOGGER.info("Slow ("+elapsed+"s) query. "+logAppendix(resourceId, query, -1));
            }
        }
        if (verbose && candidates.size() == 0 && noCandidates < 100) {
            noCandidates++;
            LOGGER.warn("No candidates. "+logAppendix(resourceId, query, -1));
        }
        if (!verbose) {
            checkWatch();
        }
        processed.incrementAndGet();
        return candidates;
    }

    @Override
    public void close() throws Exception {

    }

    private static final String DB_CONNECTION = "db-connection";
    private static final String DB_USER = "db-user";
    private static final String DB_PASSWORD = "db-password";
    private static final String ENDPOINT = "endpoint";
    private static final String QUERY = "query";

    private static CommandLine.Parser provideParameterList() {
        return CommandLine.parser()
                .withOption("c", DB_CONNECTION,
                        "connection string for the database", "DB",
                        CommandLine.Type.STRING, true, false, true)
                .withOption(null, DB_USER,
                        "user for the database", "USER",
                        CommandLine.Type.STRING, true, false, true)
                .withOption(null, DB_PASSWORD,
                        "password for the database", "PASSWORD",
                        CommandLine.Type.STRING, true, false, true)
                .withOption(null, ENDPOINT,
                        "URL to SPARQL endpoint", "ENDPOINT",
                        CommandLine.Type.STRING, true, false, true)
                .withOption(null, QUERY,
                        "Query", "QUERY",
                        CommandLine.Type.STRING, true, false, false);
    }

    public static void main(String[] args) throws Exception {
        try {
            // Parse command line
            final CommandLine cmd = provideParameterList().parse(args);

            final String dbConnection = cmd.getOptionValue(DB_CONNECTION, String.class);
            final String dbUser = cmd.getOptionValue(DB_USER, String.class);
            final String dbPassword = cmd.getOptionValue(DB_PASSWORD, String.class);
            final String endpointUri = cmd.getOptionValue(ENDPOINT, String.class);
            String query = cmd.getOptionValue(QUERY, String.class);
            if (query == null) {
                query = "http://wikidata.dbpedia.org/resource/Q359442";
            }

            FullyResolvedEntry entry = new FullyResolvedEntry(new DatasetEntry(query, null));
            new FillFromIndex(new Endpoint(endpointUri), new AllNamesStrategy(), dbConnection, dbUser, dbPassword).fill(entry);

            LOGGER.info("List of candidates for entity: "+entry.entry.resourceId);
            for (User candidate : entry.candidates) {
                LOGGER.info("  "+candidate.getName()+" (@"+candidate.getScreenName()+")");
            }
        } catch (final Throwable ex) {
            // Handle exception
            CommandLine.fail(ex);
        }
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void turnOffVerbose() {
        this.verbose = false;
    }

    public QueryAssemblyStrategy getQaStrategy() {
        return qaStrategy;
    }
}
