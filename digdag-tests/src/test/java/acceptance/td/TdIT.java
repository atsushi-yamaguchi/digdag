package acceptance.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.treasuredata.client.TDClient;
import io.digdag.client.DigdagClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.CommandStatus;
import utils.TemporaryDigdagServer;
import utils.TestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static acceptance.td.Secrets.ENCRYPTION_KEY;
import static acceptance.td.Secrets.TD_API_KEY;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Values.CLOSE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static utils.TestUtils.attemptSuccess;
import static utils.TestUtils.copyResource;
import static utils.TestUtils.expect;
import static utils.TestUtils.main;
import static utils.TestUtils.objectMapper;
import static utils.TestUtils.pushAndStart;
import static utils.TestUtils.startRequestTrackingProxy;

public class TdIT
{
    private static final Logger logger = LoggerFactory.getLogger(TdIT.class);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path config;
    private Path projectDir;

    private TDClient client;
    private String database;

    private Path outfile;

    private HttpProxyServer proxyServer;

    private TemporaryDigdagServer server;
    private String noTdConf;

    private Map<String, String> env;

    @Before
    public void setUp()
            throws Exception
    {
        assumeThat(TD_API_KEY, not(isEmptyOrNullString()));
        noTdConf = folder.newFolder().toPath().resolve("non-existing-td.conf").toString();
        projectDir = folder.getRoot().toPath().toAbsolutePath().normalize();
        config = folder.newFile().toPath();
        Files.write(config, asList("secrets.td.apikey = " + TD_API_KEY));
        outfile = projectDir.resolve("outfile");

        env = new HashMap<>();
        env.put("TD_CONFIG_PATH", noTdConf);

        client = TDClient.newBuilder(false)
                .setApiKey(TD_API_KEY)
                .build();
        database = "tmp_" + UUID.randomUUID().toString().replace('-', '_');
        client.createDatabase(database);
    }

    @After
    public void deleteDatabase()
            throws Exception
    {
        if (client != null && database != null) {
            client.deleteDatabase(database);
        }
    }

    @After
    public void stopProxy()
            throws Exception
    {
        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }
    }

    @After
    public void stopServer()
            throws Exception
    {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    public void testRunQuery()
            throws Exception
    {
        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        runWorkflow();
    }

    @Test
    public void testStoreLastResult()
            throws Exception
    {
        copyResource("acceptance/td/td/td_store_last_result.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        runWorkflow();
        JsonNode result = objectMapper().readTree(outfile.toFile());
        assertThat(result.get("last_job_id").asInt(), is(not(0)));
        assertThat(result.get("last_results").isObject(), is(true));
        assertThat(result.get("last_results").get("a").asInt(), is(1));
        assertThat(result.get("last_results").get("b").asInt(), is(2));
    }

    @Test
    public void testRunQueryWithEnvProxy()
            throws Exception
    {
        List<FullHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

        proxyServer = startRequestTrackingProxy(requests);

        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        String proxyUrl = "http://" + proxyServer.getListenAddress().getHostString() + ":" + proxyServer.getListenAddress().getPort();
        env.put("http_proxy", proxyUrl);
        runWorkflow("td.use_ssl=false");
        assertThat(requests.stream().filter(req -> req.getUri().contains("/v3/job/issue")).count(), is(greaterThan(0L)));
    }

    @Test
    public void testRunQueryAndPickUpApiKeyFromTdConf()
            throws Exception
    {
        List<FullHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

        proxyServer = startRequestTrackingProxy(requests);

        // Write apikey to td.conf
        Path tdConf = folder.newFolder().toPath().resolve("td.conf");
        Files.write(tdConf, asList(
                "[account]",
                "  user = foo@bar.com",
                "  apikey = " + TD_API_KEY,
                "  usessl = false"
        ));

        // Remove apikey from digdag conf
        Files.write(config, emptyList());

        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        String proxyUrl = "http://" + proxyServer.getListenAddress().getHostString() + ":" + proxyServer.getListenAddress().getPort();
        env.put("http_proxy", proxyUrl);
        env.put("TD_CONFIG_PATH", tdConf.toString());
        runWorkflow();
        List<FullHttpRequest> issueRequests = requests.stream().filter(req -> req.getUri().contains("/v3/job/issue")).collect(Collectors.toList());
        assertThat(issueRequests.size(), is(greaterThan(0)));
        for (FullHttpRequest request : issueRequests) {
            assertThat(request.headers().get(HttpHeaders.Names.AUTHORIZATION), is("TD1 " + TD_API_KEY));
        }
        assertThat(requests.stream().filter(req -> req.getUri().contains("/v3/job/issue")).count(), is(greaterThan(0L)));
    }

    @Test
    public void testRunQueryOnServerWithEnvProxy()
            throws Exception
    {
        List<FullHttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

        proxyServer = startRequestTrackingProxy(requests);

        String proxyUrl = "http://" + proxyServer.getListenAddress().getHostString() + ":" + proxyServer.getListenAddress().getPort();

        TemporaryDigdagServer server = TemporaryDigdagServer.builder()
                .configuration(Secrets.secretsServerConfiguration())
                .environment(ImmutableMap.of("http_proxy", proxyUrl))
                .build();

        server.start();

        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));

        int projectId = TestUtils.pushProject(server.endpoint(), projectDir);

        DigdagClient digdagClient = DigdagClient.builder()
                .host(server.host())
                .port(server.port())
                .build();

        digdagClient.setProjectSecret(projectId, "td.apikey", TD_API_KEY);

        long attemptId = pushAndStart(server.endpoint(), projectDir, "workflow", ImmutableMap.of(
                "outfile", outfile.toString(),
                "td.use_ssl", "false"));

        expect(Duration.ofMinutes(5), attemptSuccess(server.endpoint(), attemptId));

        assertThat(requests.stream().filter(req -> req.getUri().contains("/v3/job/issue")).count(), is(greaterThan(0L)));
    }

    @Test
    public void testRunQueryOnServerWithoutSecretAccessPolicy()
            throws Exception
    {
        TemporaryDigdagServer server = TemporaryDigdagServer.builder()
                .configuration("digdag.secret-encryption-key = " + ENCRYPTION_KEY)
                .build();

        server.start();

        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));

        int projectId = TestUtils.pushProject(server.endpoint(), projectDir);

        DigdagClient digdagClient = DigdagClient.builder()
                .host(server.host())
                .port(server.port())
                .build();

        digdagClient.setProjectSecret(projectId, "td.apikey", TD_API_KEY);

        long attemptId = pushAndStart(server.endpoint(), projectDir, "workflow", ImmutableMap.of(
                "outfile", outfile.toString(),
                "td.use_ssl", "false"));

        expect(Duration.ofMinutes(5), attemptSuccess(server.endpoint(), attemptId));
    }

    @Test
    public void testRunQueryWithPreview()
            throws Exception
    {
        copyResource("acceptance/td/td/td_preview.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        runWorkflow();
    }

    @Test
    public void testRunQueryWithPreviewAndCreateTable()
            throws Exception
    {
        copyResource("acceptance/td/td/td_preview_create_table.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        runWorkflow("td.database=" + database);
    }

    @Test
    public void testRunQueryLegacyApikeyParam()
            throws Exception
    {
        // TODO: Remove this test when the legacy support for td.apikey param is removed

        // Replace the "secrets.td.apikey" entry with the legacy "params.td.apikey" entry
        Files.write(config, asList("params.td.apikey = " + TD_API_KEY));

        copyResource("acceptance/td/td/td.dig", projectDir.resolve("workflow.dig"));
        copyResource("acceptance/td/td/query.sql", projectDir.resolve("query.sql"));
        runWorkflow();
    }

    @Test
    public void testRunQueryInline()
            throws Exception
    {
        copyResource("acceptance/td/td/td_inline.dig", projectDir.resolve("workflow.dig"));
        runWorkflow();
    }

    @Test
    public void testRetries()
            throws Exception
    {
        int failures = 3;

        List<FullHttpRequest> jobIssueRequests = Collections.synchronizedList(new ArrayList<>());

        proxyServer = DefaultHttpProxyServer
                .bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter()
                {
                    @Override
                    public int getMaximumRequestBufferSizeInBytes()
                    {
                        return 1024 * 1024;
                    }

                    @Override
                    public HttpFilters filterRequest(HttpRequest httpRequest, ChannelHandlerContext channelHandlerContext)
                    {
                        return new HttpFiltersAdapter(httpRequest)
                        {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject)
                            {
                                assert httpObject instanceof FullHttpRequest;
                                FullHttpRequest fullHttpRequest = (FullHttpRequest) httpObject;
                                if (httpRequest.getUri().contains("/v3/job/issue")) {
                                    jobIssueRequests.add(fullHttpRequest.copy());
                                    if (jobIssueRequests.size() < failures) {
                                        logger.info("Simulating 500 INTERNAL SERVER ERROR for request: {}", httpRequest);
                                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(httpRequest.getProtocolVersion(), INTERNAL_SERVER_ERROR);
                                        response.headers().set(CONNECTION, CLOSE);
                                        return response;
                                    }
                                }
                                logger.info("Passing request: {}", httpRequest);
                                return null;
                            }
                        };
                    }
                }).start();

        Files.write(config, asList(
                "params.td.use_ssl = false",
                "params.td.proxy.enabled = true",
                "params.td.proxy.host = " + proxyServer.getListenAddress().getHostString(),
                "params.td.proxy.port = " + proxyServer.getListenAddress().getPort()
        ), APPEND);

        copyResource("acceptance/td/td/td_inline.dig", projectDir.resolve("workflow.dig"));
        runWorkflow();

        for (FullHttpRequest request : jobIssueRequests) {
            ReferenceCountUtil.releaseLater(request);
        }

        assertThat(jobIssueRequests.size(), is(not(0)));

        // Verify that all job issue requests reuse the same domain key
        verifyDomainKeys(jobIssueRequests);
    }

    @Test
    public void testDomainKeyConflict()
            throws Exception
    {
        List<FullHttpRequest> jobIssueRequests = Collections.synchronizedList(new ArrayList<>());
        List<FullHttpResponse> jobIssueResponses = Collections.synchronizedList(new ArrayList<>());

        proxyServer = DefaultHttpProxyServer
                .bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter()
                {
                    @Override
                    public int getMaximumRequestBufferSizeInBytes()
                    {
                        return 1024 * 1024;
                    }

                    @Override
                    public int getMaximumResponseBufferSizeInBytes()
                    {
                        return 1024 * 1024;
                    }

                    @Override
                    public HttpFilters filterRequest(HttpRequest httpRequest, ChannelHandlerContext channelHandlerContext)
                    {
                        return new HttpFiltersAdapter(httpRequest)
                        {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject)
                            {
                                assert httpObject instanceof FullHttpRequest;
                                FullHttpRequest fullHttpRequest = (FullHttpRequest) httpObject;
                                if (httpRequest.getUri().contains("/v3/job/issue")) {
                                    jobIssueRequests.add(fullHttpRequest.copy());
                                }
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject)
                            {
                                assert httpObject instanceof FullHttpResponse;
                                FullHttpResponse fullHttpResponse = (FullHttpResponse) httpObject;

                                // Let the first issue request through so that the job is started and the domain key is recorded by the td api but
                                // simulate a failure for the first request. The td operator will then retry starting the job with the same domain key
                                // which will result in a conflict response. The td operator should then interpret that as the job having successfully been issued.
                                if (httpRequest.getUri().contains("/v3/job/issue")) {
                                    jobIssueResponses.add(fullHttpResponse);
                                    if (jobIssueResponses.size() == 1) {
                                        logger.info("Simulating 500 INTERNAL SERVER ERROR for request: {}", httpRequest);
                                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(httpRequest.getProtocolVersion(), INTERNAL_SERVER_ERROR);
                                        response.headers().set(CONNECTION, CLOSE);
                                        return response;
                                    }
                                }
                                logger.info("Passing request: {}", httpRequest);
                                return httpObject;
                            }
                        };
                    }
                }).start();

        Files.write(config, asList(
                "params.td.use_ssl = false",
                "params.td.proxy.enabled = true",
                "params.td.proxy.host = " + proxyServer.getListenAddress().getHostString(),
                "params.td.proxy.port = " + proxyServer.getListenAddress().getPort()
        ), APPEND);

        copyResource("acceptance/td/td/td_inline.dig", projectDir.resolve("workflow.dig"));
        runWorkflow();

        for (FullHttpRequest request : jobIssueRequests) {
            ReferenceCountUtil.releaseLater(request);
        }

        assertThat(jobIssueRequests.size(), is(not(0)));
        assertThat(jobIssueResponses.size(), is(not(0)));

        verifyDomainKeys(jobIssueRequests);
    }

    private void verifyDomainKeys(List<FullHttpRequest> jobIssueRequests)
            throws IOException
    {
        // Verify that all job issue requests reuse the same domain key
        FullHttpRequest firstRequest = jobIssueRequests.get(0);
        String domainKey = domainKey(firstRequest);

        for (int i = 0; i < jobIssueRequests.size(); i++) {
            FullHttpRequest request = jobIssueRequests.get(i);
            String requestDomainKey = domainKey(request);
            assertThat(requestDomainKey, is(domainKey));
        }
    }

    private String domainKey(FullHttpRequest request)
            throws IOException
    {
        FullHttpRequest copy = request.copy();
        ReferenceCountUtil.releaseLater(copy);
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(copy);
        List<InterfaceHttpData> keyDatas = decoder.getBodyHttpDatas("domain_key");
        assertThat(keyDatas, is(not(nullValue())));
        assertThat(keyDatas.size(), is(1));
        InterfaceHttpData domainKeyData = keyDatas.get(0);
        assertThat(domainKeyData.getHttpDataType(), is(HttpDataType.Attribute));
        return ((Attribute) domainKeyData).getValue();
    }

    private CommandStatus runWorkflow(String... params)
    {
        List<String> args = new ArrayList<>();
        args.addAll(asList("run",
                "-o", projectDir.toString(),
                "--config", config.toString(),
                "--project", projectDir.toString(),
                "-p", "outfile=" + outfile));

        for (String param : params) {
            args.add("-p");
            args.add(param);
        }

        args.add("workflow.dig");

        CommandStatus runStatus = main(env, args);

        assertThat(runStatus.errUtf8(), runStatus.code(), is(0));

        assertThat(Files.exists(outfile), is(true));

        return runStatus;
    }
}
