package no.novari.qliktosharepoint.config;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AzureIdentityAuthenticationProvider;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@Setter
@RequiredArgsConstructor
public class GraphClientConfig {

    private final GraphProperties graphProperties;
    private int timeout;

    @Bean
    public GraphServiceClient graphServiceClient() {
        log.debug("Starting PostConstruct of GraphServiceClient");
        String[] scopes = new String[]{"https://graph.microsoft.com/.default"};


        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(graphProperties.getClientId())
                .clientSecret(graphProperties.getClientSecret())
                .tenantId(graphProperties.getTenantId())
                .build();

        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(64);

        okhttp3.ConnectionPool pool = new okhttp3.ConnectionPool(
                100, 5, TimeUnit.MINUTES);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .callTimeout(timeout, TimeUnit.MINUTES)
                .connectTimeout(timeout, TimeUnit.MINUTES)
                .readTimeout(timeout, TimeUnit.MINUTES)
                .writeTimeout(timeout, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .build();

        if (null == credential) {
            log.error("Unexpected error");
        }

        assert credential != null;
        return new GraphServiceClient(new AzureIdentityAuthenticationProvider(credential, new String[0], scopes), okHttpClient);
    }

    @Bean
    public ClientSecretCredential graphCredential() {
        return new ClientSecretCredentialBuilder()
                .clientId(graphProperties.getClientId())
                .clientSecret(graphProperties.getClientSecret())
                .tenantId(graphProperties.getTenantId())
                .build();
    }

    @Bean
    public OkHttpClient graphOkHttpClient() {
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(64);

        okhttp3.ConnectionPool pool = new okhttp3.ConnectionPool(100, 5, TimeUnit.MINUTES);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .callTimeout(timeout, TimeUnit.MINUTES)
                .connectTimeout(timeout, TimeUnit.MINUTES)
                .readTimeout(timeout, TimeUnit.MINUTES)
                .writeTimeout(timeout, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .build();
    }

}