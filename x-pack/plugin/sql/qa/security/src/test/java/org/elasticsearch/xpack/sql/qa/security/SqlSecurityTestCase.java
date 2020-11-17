/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.qa.security;

import org.apache.lucene.util.SuppressForbidden;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.action.admin.indices.get.GetIndexAction;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesAction;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.core.security.index.RestrictedIndicesNames;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Before;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

public abstract class SqlSecurityTestCase extends ESRestTestCase {
    /**
     * Actions taken by this test.
     * <p>
     * For methods that take {@code user} a {@code null} user means "use the admin".
     */
    protected interface Actions {
        String minimalPermissionsForAllActions();
        void queryWorksAsAdmin() throws Exception;
        /**
         * Assert that running some sql as a user returns the same result as running it as
         * the administrator.
         */
        void expectMatchesAdmin(String adminSql, String user, String userSql) throws Exception;
        /**
         * Same as {@link #expectMatchesAdmin(String, String, String)} but sets the scroll size
         * to 1 and completely scrolls the results.
         */
        void expectScrollMatchesAdmin(String adminSql, String user, String userSql) throws Exception;
        void expectDescribe(Map<String, List<String>> columns, String user) throws Exception;
        void expectShowTables(List<String> tables, String user) throws Exception;
        void expectForbidden(String user, String sql) throws Exception;
        void expectUnknownIndex(String user, String sql) throws Exception;
        void expectUnknownColumn(String user, String sql, String column) throws Exception;
        void checkNoMonitorMain(String user) throws Exception;
    }

    protected static final String SQL_ACTION_NAME = "indices:data/read/sql";
    /**
     * Location of the audit log file. We could technically figure this out by reading the admin
     * APIs but it isn't worth doing because we also have to give ourselves permission to read
     * the file and that must be done by setting a system property and reading it in
     * {@code plugin-security.policy}. So we may as well have gradle set the property.
     */
    private static final Path AUDIT_LOG_FILE = lookupAuditLog();
    private static final Path ROLLED_OVER_AUDIT_LOG_FILE = lookupRolledOverAuditLog();

    @SuppressForbidden(reason="security doesn't work with mock filesystem")
    private static Path lookupAuditLog() {
        String auditLogFileString = System.getProperty("tests.audit.logfile");
        if (null == auditLogFileString) {
            throw new IllegalStateException("tests.audit.logfile must be set to run this test. It is automatically "
                    + "set by gradle. If you must set it yourself then it should be the absolute path to the audit "
                    + "log file generated by running x-pack with audit logging enabled.");
        }
        return Paths.get(auditLogFileString);
    }
    
    @SuppressForbidden(reason="security doesn't work with mock filesystem")
    private static Path lookupRolledOverAuditLog() {
        String auditLogFileString = System.getProperty("tests.audit.yesterday.logfile");
        if (null == auditLogFileString) {
            throw new IllegalStateException("tests.audit.yesterday.logfile must be set to run this test. It should be automatically "
                    + "set by gradle.");
        }        
        return Paths.get(auditLogFileString);
    }

    private static boolean oneTimeSetup = false;
    private static boolean auditFailure = false;

    /**
     * The actions taken by this test.
     */
    private final Actions actions;

    /**
     * How much of the audit log was written before the test started.
     */
    private static long auditLogWrittenBeforeTestStart;
    
    /**
     * If the audit log file rolled over. This is a rare case possible only at midnight.
     */
    private static boolean auditFileRolledOver = false;

    public SqlSecurityTestCase(Actions actions) {
        this.actions = actions;
    }

    /**
     * All tests run as a an administrative user but use
     * <code>es-security-runas-user</code> to become a less privileged user when needed.
     */
    @Override
    protected Settings restClientSettings() {
        return RestSqlIT.securitySettings();
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        /* We can't wipe the cluster between tests because that nukes the audit
         * trail index which makes the auditing flaky. Instead we wipe all
         * indices after the entire class is finished. */
        return true;
    }

    @Before
    public void oneTimeSetup() throws Exception {
        if (oneTimeSetup) {
            /* Since we don't wipe the cluster between tests we only need to
             * write the test data once. */
            return;
        }
        Request request = new Request("PUT", "/_bulk");
        request.addParameter("refresh", "true");

        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_index\": \"test\", \"_type\": \"doc\", \"_id\":\"1\"}\n");
        bulk.append("{\"a\": 1, \"b\": 2, \"c\": 3}\n");
        bulk.append("{\"index\":{\"_index\": \"test\", \"_type\": \"doc\", \"_id\":\"2\"}\n");
        bulk.append("{\"a\": 4, \"b\": 5, \"c\": 6}\n");
        bulk.append("{\"index\":{\"_index\": \"bort\", \"_type\": \"doc\", \"_id\":\"1\"}\n");
        bulk.append("{\"a\": \"test\"}\n");
        request.setJsonEntity(bulk.toString());
        client().performRequest(request);
        oneTimeSetup = true;
    }

    @Before
    public void setInitialAuditLogOffset() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            if (false == Files.exists(AUDIT_LOG_FILE)) {
                auditLogWrittenBeforeTestStart = 0;
                return null;
            }
            if (false == Files.isRegularFile(AUDIT_LOG_FILE)) {
                throw new IllegalStateException("expected tests.audit.logfile [" + AUDIT_LOG_FILE + "] to be a plain file but wasn't");
            }
            try {
                auditLogWrittenBeforeTestStart = Files.size(AUDIT_LOG_FILE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            
            // The log file can roll over without being caught by assertLogs() method: in those tests where exceptions are being handled
            // and no audit logs being read (and, thus, assertLogs() is not called) - for example testNoMonitorMain() method: there are no
            // calls to auditLogs(), and the method could run while the audit file is rolled over.
            // If this happens, next call to auditLogs() will make the tests read from the rolled over file using the main audit file
            // offset, which will most likely not going to work since the offset will happen somewhere in the middle of a json line.
            if (auditFileRolledOver == false && Files.exists(ROLLED_OVER_AUDIT_LOG_FILE)) {
                // once the audit file rolled over, it will stay like this
                auditFileRolledOver = true;
            }
            return null;
        });
    }

    @AfterClass
    public static void wipeIndicesAfterTests() throws IOException {
        try {
            adminClient().performRequest(new Request("DELETE", "*"));
        } catch (ResponseException e) {
            // 404 here just means we had no indexes
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                throw e;
            }
        } finally {
            // Clear the static state so other subclasses can reuse it later
            oneTimeSetup = false;
            auditFailure = false;
        }
    }

    @Override
    protected String getProtocol() {
        return RestSqlIT.SSL_ENABLED ? "https" : "http";
    }

    public void testQueryWorksAsAdmin() throws Exception {
        actions.queryWorksAsAdmin();
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .assertLogs();
    }

    public void testQueryWithFullAccess() throws Exception {
        createUser("full_access", actions.minimalPermissionsForAllActions());

        actions.expectMatchesAdmin("SELECT * FROM test ORDER BY a", "full_access", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("full_access", "test")
            .assertLogs();
    }

    public void testScrollWithFullAccess() throws Exception {
        createUser("full_access", actions.minimalPermissionsForAllActions());

        actions.expectScrollMatchesAdmin("SELECT * FROM test ORDER BY a", "full_access", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scroll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlCompositeActionFieldCaps("full_access", "test")
            .expect(true, SQL_ACTION_NAME, "full_access", empty())
            .expect(true, SQL_ACTION_NAME, "full_access", empty())
            .assertLogs();
    }

    public void testQueryNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "SELECT * FROM test");
        createAuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testQueryWrongAccess() throws Exception {
        createUser("wrong_access", "read_something_else");

        actions.expectUnknownIndex("wrong_access", "SELECT * FROM test");
        createAuditLogAsserter()
            //This user has permission to run sql queries so they are given preliminary authorization
            .expect(true, SQL_ACTION_NAME, "wrong_access", empty())
            //the following get index is granted too but against the no indices placeholder, as ignore_unavailable=true
            .expect(true, FieldCapabilitiesAction.NAME, "wrong_access", hasItems("*", "-*"))
            .assertLogs();
    }

    public void testQuerySingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectMatchesAdmin("SELECT a FROM test ORDER BY a", "only_a", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("only_a", "test")
            .assertLogs();
    }

    public void testScrollWithSingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectScrollMatchesAdmin("SELECT a FROM test ORDER BY a", "only_a", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scroll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlCompositeActionFieldCaps("only_a", "test")
            .expect(true, SQL_ACTION_NAME, "only_a", empty())
            .expect(true, SQL_ACTION_NAME, "only_a", empty())
            .assertLogs();
    }

    public void testQueryStringSingleFieldGrantedWrongRequested() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectUnknownColumn("only_a", "SELECT c FROM test", "c");
        /* The user has permission to query the index but one of the
         * columns that they explicitly mention is hidden from them
         * by field level access control. This *looks* like a successful
         * query from the audit side because all the permissions checked
         * out but it failed in SQL because it couldn't compile the
         * query without the metadata for the missing field. */
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("only_a", "test")
            .assertLogs();
    }

    public void testQuerySingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectMatchesAdmin("SELECT a, b FROM test ORDER BY a", "not_c", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("not_c", "test")
            .assertLogs();
    }

    public void testScrollWithSingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectScrollMatchesAdmin("SELECT a, b FROM test ORDER BY a", "not_c", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scroll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlCompositeActionFieldCaps("not_c", "test")
            .expect(true, SQL_ACTION_NAME, "not_c", empty())
            .expect(true, SQL_ACTION_NAME, "not_c", empty())
            .assertLogs();
    }

    public void testQuerySingleFieldExceptionedWrongRequested() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectUnknownColumn("not_c", "SELECT c FROM test", "c");
        /* The user has permission to query the index but one of the
         * columns that they explicitly mention is hidden from them
         * by field level access control. This *looks* like a successful
         * query from the audit side because all the permissions checked
         * out but it failed in SQL because it couldn't compile the
         * query without the metadata for the missing field. */
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("not_c", "test")
            .assertLogs();
    }

    public void testQueryDocumentExcluded() throws Exception {
        createUser("no_3s", "read_test_without_c_3");

        actions.expectMatchesAdmin("SELECT * FROM test WHERE c != 3 ORDER BY a", "no_3s", "SELECT * FROM test ORDER BY a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("no_3s", "test")
            .assertLogs();
    }

    public void testShowTablesWorksAsAdmin() throws Exception {
        actions.expectShowTables(Arrays.asList("bort", "test"), null);
        createAuditLogAsserter()
            .expectSqlCompositeActionGetIndex("test_admin", "bort", "test")
            .assertLogs();
    }

    public void testShowTablesWorksAsFullAccess() throws Exception {
        createUser("full_access", actions.minimalPermissionsForAllActions());

        actions.expectMatchesAdmin("SHOW TABLES LIKE '%t'", "full_access", "SHOW TABLES");
        createAuditLogAsserter()
            .expectSqlCompositeActionGetIndex("test_admin", "bort", "test")
            .expectSqlCompositeActionGetIndex("full_access", "bort", "test")
            .assertLogs();
    }

    public void testShowTablesWithNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "SHOW TABLES");
        createAuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testShowTablesWithLimitedAccess() throws Exception {
        createUser("read_bort", "read_bort");

        actions.expectMatchesAdmin("SHOW TABLES LIKE 'bort'", "read_bort", "SHOW TABLES");
        createAuditLogAsserter()
             .expectSqlCompositeActionGetIndex("test_admin", "bort").expectSqlCompositeActionGetIndex("read_bort", "bort")
            .assertLogs();
    }

    public void testShowTablesWithLimitedAccessUnaccessableIndex() throws Exception {
        createUser("read_bort", "read_bort");

        actions.expectMatchesAdmin("SHOW TABLES LIKE 'not-created'", "read_bort", "SHOW TABLES LIKE 'test'");
        createAuditLogAsserter()
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, GetIndexAction.NAME, "test_admin", contains("*", "-*"))
            .expect(true, SQL_ACTION_NAME, "read_bort", empty())
            .expect(true, GetIndexAction.NAME, "read_bort", contains("*", "-*"))
            .assertLogs();
    }

    public void testDescribeWorksAsAdmin() throws Exception {
        Map<String, List<String>> expected = new TreeMap<>();
        expected.put("a", asList("BIGINT", "long"));
        expected.put("b", asList("BIGINT", "long"));
        expected.put("c", asList("BIGINT", "long"));
        actions.expectDescribe(expected, null);
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .assertLogs();
    }

    public void testDescribeWorksAsFullAccess() throws Exception {
        createUser("full_access", actions.minimalPermissionsForAllActions());

        actions.expectMatchesAdmin("DESCRIBE test", "full_access", "DESCRIBE test");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("full_access", "test")
            .assertLogs();
    }

    public void testDescribeWithNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "DESCRIBE test");
        createAuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testDescribeWithWrongAccess() throws Exception {
        createUser("wrong_access", "read_something_else");

        actions.expectDescribe(Collections.emptyMap(), "wrong_access");
        createAuditLogAsserter()
            //This user has permission to run sql queries so they are given preliminary authorization
            .expect(true, SQL_ACTION_NAME, "wrong_access", empty())
            //the following get index is granted too but against the no indices placeholder, as ignore_unavailable=true
            .expect(true, FieldCapabilitiesAction.NAME, "wrong_access", hasItems("*", "-*"))
            .assertLogs();
    }

    public void testDescribeSingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectDescribe(singletonMap("a", asList("BIGINT", "long")), "only_a");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("only_a", "test")
            .assertLogs();
    }

    public void testDescribeSingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        Map<String, List<String>> expected = new TreeMap<>();
        expected.put("a", asList("BIGINT", "long"));
        expected.put("b", asList("BIGINT", "long"));
        actions.expectDescribe(expected, "not_c");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("not_c", "test")
            .assertLogs();
    }

    public void testDescribeDocumentExcluded() throws Exception {
        createUser("no_3s", "read_test_without_c_3");

        actions.expectMatchesAdmin("DESCRIBE test", "no_3s", "DESCRIBE test");
        createAuditLogAsserter()
            .expectSqlCompositeActionFieldCaps("test_admin", "test")
            .expectSqlCompositeActionFieldCaps("no_3s", "test")
            .assertLogs();
    }

    public void testNoMonitorMain() throws Exception {
        createUser("no_monitor_main", "no_monitor_main");
        actions.checkNoMonitorMain("no_monitor_main");
    }

    public void testNoGetIndex() throws Exception {
        createUser("no_get_index", "no_get_index");

        actions.expectForbidden("no_get_index", "SELECT * FROM test");
        actions.expectForbidden("no_get_index", "SHOW TABLES LIKE 'test'");
        actions.expectForbidden("no_get_index", "DESCRIBE test");
    }

    protected static void createUser(String name, String role) throws IOException {
        Request request = new Request("PUT", "/_xpack/security/user/" + name);
        XContentBuilder user = JsonXContent.contentBuilder().prettyPrint();
        user.startObject(); {
            user.field("password", "testpass");
            user.field("roles", role);
        }
        user.endObject();
        request.setJsonEntity(Strings.toString(user));
        client().performRequest(request);
    }

    protected AuditLogAsserter createAuditLogAsserter() {
        return new AuditLogAsserter();
    }

    /**
     * Used to assert audit logs. Logs are asserted to match in any order because
     * we don't always scroll in the same order but each log checker must match a
     * single log and all logs must be matched.
     */
    protected class AuditLogAsserter {
        protected final List<Function<Map<String, Object>, Boolean>> logCheckers = new ArrayList<>();

        public AuditLogAsserter expectSqlCompositeActionGetIndex(String user, String... indices) {
            expect(true, SQL_ACTION_NAME, user, empty());
            expect(true, GetIndexAction.NAME, user, hasItems(indices));
            return this;
        }

        public AuditLogAsserter expectSqlCompositeActionFieldCaps(String user, String... indices) {
            expect(true, SQL_ACTION_NAME, user, empty());
            expect(true, FieldCapabilitiesAction.NAME, user, hasItems(indices));
            return this;
        }

        public AuditLogAsserter expect(boolean granted, String action, String principal,
                    Matcher<? extends Iterable<? extends String>> indicesMatcher) {
            String request;
            switch (action) {
            case SQL_ACTION_NAME:
                request = "SqlQueryRequest";
                break;
            case GetIndexAction.NAME:
                request = GetIndexRequest.class.getSimpleName();
                break;
                case FieldCapabilitiesAction.NAME:
                    request = FieldCapabilitiesRequest.class.getSimpleName();
                    break;
            default:
                throw new IllegalArgumentException("Unknown action [" + action + "]");
            }
            final String eventAction = granted ? "access_granted" : "access_denied";
            final String realm = principal.equals("test_admin") ? "default_file" : "default_native";
            return expect(eventAction, action, principal, realm, indicesMatcher, request);
        }

        public AuditLogAsserter expect(String eventAction, String action, String principal, String realm,
                    Matcher<? extends Iterable<? extends String>> indicesMatcher, String request) {
            logCheckers.add(m ->
                eventAction.equals(m.get("event.action"))
                && action.equals(m.get("action"))
                && principal.equals(m.get("user.name"))
                && realm.equals(m.get("user.realm"))
                && Matchers.nullValue(String.class).matches(m.get("user.run_by.name"))
                && Matchers.nullValue(String.class).matches(m.get("user.run_by.realm"))
                && indicesMatcher.matches(m.get("indices"))
                && request.equals(m.get("request.name"))
            );
            return this;
        }

        public void assertLogs() throws Exception {
            assertFalse("Previous test had an audit-related failure. All subsequent audit related assertions are bogus because we can't "
                    + "guarantee that we fully cleaned up after the last test.", auditFailure);
            try {
                // use a second variable since the `assertBusy()` block can be executed multiple times and the
                // static auditFileRolledOver value can change and mess up subsequent calls of this code block
                boolean localAuditFileRolledOver = auditFileRolledOver;
                assertBusy(() -> {
                    SecurityManager sm = System.getSecurityManager();
                    if (sm != null) {
                        sm.checkPermission(new SpecialPermission());
                    }
                    
                    BufferedReader[] logReaders = new BufferedReader[2];
                    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                        try {
                            // the audit log file rolled over during the test
                            // and we need to consume the rest of the rolled over file plus the new audit log file
                            if (localAuditFileRolledOver == false && Files.exists(ROLLED_OVER_AUDIT_LOG_FILE)) {
                                // once the audit file rolled over, it will stay like this
                                auditFileRolledOver = true;
                                // the order in the array matters, as the readers will be used in that order
                                logReaders[0] = Files.newBufferedReader(ROLLED_OVER_AUDIT_LOG_FILE, StandardCharsets.UTF_8);
                            }
                            logReaders[1] = Files.newBufferedReader(AUDIT_LOG_FILE, StandardCharsets.UTF_8);
                            return null;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    
                    // The "index" is used as a way of reading from both rolled over file and current audit file in order: rolled over file
                    // first, then the audit log file. Very rarely we will read from the rolled over file: when the test happened to run
                    // at midnight and the audit file rolled over during the test.
                    int index;
                    if (logReaders[0] != null) {
                        logReaders[0].skip(auditLogWrittenBeforeTestStart);
                        // start with the rolled over file first
                        index = 0;
                    } else {
                        // the current audit log file reader should always be non-null
                        logReaders[1].skip(auditLogWrittenBeforeTestStart);
                        // start with the current audit logging file
                        index = 1;
                    }

                    List<Map<String, Object>> logs = new ArrayList<>();
                    String line;
                    while (index < 2) {
                        line = logReaders[index].readLine();
                        // when the end of the file is reached, either stop or move to the next reader
                        if (line == null) {
                            if (++index == 2) {
                                break;
                            }
                        }
                        else {
                            try {
                                final Map<String, Object> log = XContentHelper.convertToMap(JsonXContent.jsonXContent, line, false);
                                if (false == ("access_denied".equals(log.get("event.action"))
                                        || "access_granted".equals(log.get("event.action")))) {
                                    continue;
                                }
                                assertThat(log.containsKey("action"), is(true));
                                if (false == (SQL_ACTION_NAME.equals(log.get("action"))
                                              || GetIndexAction.NAME.equals(log.get("action"))
                                              || FieldCapabilitiesAction.NAME.equals(log.get("action")))) {
                                    // TODO we may want to extend this and the assertions to SearchAction.NAME as well
                                    continue;
                                }
                                assertThat(log.containsKey("user.name"), is(true));
                                List<String> indices = new ArrayList<>();
                                if (log.containsKey("indices")) {
                                    indices = (ArrayList<String>) log.get("indices");
                                    if ("test_admin".equals(log.get("user.name"))) {
                                        /*
                                         * Sometimes we accidentally sneak access to the security tables. This is fine,
                                         * SQL drops them from the interface. So we might have access to them, but we
                                         * don't show them.
                                         */
                                        indices.removeAll(RestrictedIndicesNames.RESTRICTED_NAMES);
                                    }
                                }
                                // Use a sorted list for indices for consistent error reporting
                                Collections.sort(indices);
                                log.put("indices", indices);
                                logs.add(log);
                            } catch (final ElasticsearchParseException e) {
                                throw new IllegalArgumentException("Unrecognized log: " + line, e);
                            }
                        }
                    }
                    List<Map<String, Object>> allLogs = new ArrayList<>(logs);
                    List<Integer> notMatching = new ArrayList<>();
                    checker: for (int c = 0; c < logCheckers.size(); c++) {
                        Function<Map<String, Object>, Boolean> logChecker = logCheckers.get(c);
                        for (Iterator<Map<String, Object>> logsItr = logs.iterator(); logsItr.hasNext();) {
                            Map<String, Object> log = logsItr.next();
                            if (logChecker.apply(log)) {
                                logsItr.remove();
                                continue checker;
                            }
                        }
                        notMatching.add(c);
                    }
                    if (false == notMatching.isEmpty()) {
                        fail("Some checkers " + notMatching + " didn't match any logs. All logs:" + logsMessage(allLogs)
                            + "\nRemaining logs:" + logsMessage(logs));
                    }
                    if (false == logs.isEmpty()) {
                        fail("Not all logs matched. Unmatched logs:" + logsMessage(logs));
                    }
                });
            } catch (AssertionError e) {
                auditFailure = true;
                logger.warn("Failed to find an audit log. Skipping remaining tests in this class after this the missing audit"
                        + "logs could turn up later.");
                throw e;
            }
        }

        private String logsMessage(List<Map<String, Object>> logs) {
            if (logs.isEmpty()) {
                return "  none!";
            }
            StringBuilder logsMessage = new StringBuilder();
            for (Map<String, Object> log : logs) {
                logsMessage.append('\n').append(log);
            }
            return logsMessage.toString();
        }
    }
}
