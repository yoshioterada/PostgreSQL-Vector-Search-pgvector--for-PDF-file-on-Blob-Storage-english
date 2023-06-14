package com.yoshio3;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.cosmos.util.CosmosPagedFlux;
import com.yoshio3.entities.CosmosDBDocument;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class CosmosDBUtil {

    @Value("${azure.cosmos.db.endpoint}")
    private String COSMOS_DB_ENDPOINT;

    @Value("${azure.cosmos.db.key}")
    private String COSMOS_DB_KEY;

    @Value("${azure.cosmos.db.database.name}")
    private String COSMOS_DB_DATABASE_NAME;

    @Value("${azure.cosmos.db.container.name}")
    private String COSMOS_DB_CONTAINER_NAME;

    private static final String RETRIVE_REGISTERED_DOCUMENTS_QUERY = "SELECT * FROM c WHERE c.status = 'COMPLETED' ORDER BY c.fileName ASC, c.pageNumber ASC";

    private static final String RETRIVE_FAILED_DOCUMENTS_QUERY = "SELECT * FROM c WHERE c.status != 'COMPLETED' ORDER BY c.fileName ASC, c.pageNumber ASC";

    private CosmosAsyncContainer container = null;
    private CosmosAsyncClient client = null;

    @PostConstruct
    private void init() {
        client = new CosmosClientBuilder()
                .endpoint(COSMOS_DB_ENDPOINT)
                .key(COSMOS_DB_KEY)
                .buildAsyncClient();
        CosmosAsyncDatabase database = client.getDatabase(COSMOS_DB_DATABASE_NAME);
        container = database.getContainer(COSMOS_DB_CONTAINER_NAME);
    }

    // Get documents that are successfully registered in the DB
    public Mono<List<CosmosDBDocument>> getAllRegisteredDocuments() {
        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
        options.setQueryMetricsEnabled(true);
        SqlQuerySpec querySpec = new SqlQuerySpec(RETRIVE_REGISTERED_DOCUMENTS_QUERY);

        CosmosPagedFlux<CosmosDBDocument> queryItems = container.queryItems(querySpec, options, CosmosDBDocument.class);
        return queryItems.byPage().flatMap(page -> Flux.fromIterable(page.getResults())).collectList();
    }

    // Get documents that are failing to register DB
    public Mono<List<CosmosDBDocument>> getAllFailedDocuments() {
        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
        options.setQueryMetricsEnabled(true);
        SqlQuerySpec querySpec = new SqlQuerySpec(RETRIVE_FAILED_DOCUMENTS_QUERY);

        CosmosPagedFlux<CosmosDBDocument> queryItems = container.queryItems(querySpec, options, CosmosDBDocument.class);
        return queryItems.byPage().flatMap(page -> Flux.fromIterable(page.getResults())).collectList();
    }
}
