package com.yoshio3;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlQuerySpec;
import com.microsoft.azure.functions.ExecutionContext;
import com.yoshio3.models.CosmosDBDocument;
import com.yoshio3.models.CosmosDBDocumentStatus;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;

public class CosmosDBUtil {

    private static final String COSMOS_DB_ENDPOINT;
    private static final String COSMOS_DB_KEY;
    private static final String COSMOS_DB_DATABASE_NAME;
    private static final String COSMOS_DB_CONTAINER_NAME;

    static{
        COSMOS_DB_ENDPOINT = System.getenv("AzureCosmosDbEndpoint");
        COSMOS_DB_KEY = System.getenv("AzureCosmosDbKey");
        COSMOS_DB_DATABASE_NAME = System.getenv("AzureCosmosDbDatabaseName");
        COSMOS_DB_CONTAINER_NAME = System.getenv("AzureCosmosDbContainerName");
    }

    private CosmosAsyncContainer container = null;
    private CosmosAsyncClient client = null;

    public CosmosDBUtil() {
        client = new CosmosClientBuilder().endpoint(COSMOS_DB_ENDPOINT).key(COSMOS_DB_KEY)
                .buildAsyncClient();
        CosmosAsyncDatabase database = client.getDatabase(COSMOS_DB_DATABASE_NAME);
        container = database.getContainer(COSMOS_DB_CONTAINER_NAME);
    }

    public Mono<CosmosItemResponse<CosmosDBDocument>> createDocument(String id, String fileName,
            CosmosDBDocumentStatus status, int pageNumber, ExecutionContext context) {
        CosmosDBDocument document = new CosmosDBDocument(id, fileName, status, pageNumber);
        context.getLogger().info("Cosmos DB create Document: " + document);
        return container.createItem(document, null);
    }

    public void updateStatus(String id, CosmosDBDocumentStatus status, 
            ExecutionContext context) {
        context.getLogger().info("Cosmos DB update Status: Start " + id + ":" + status);
        container.readItem(id, new PartitionKey(id), CosmosDBDocument.class)
                .subscribe(responseItem -> {
                    CosmosDBDocument item = responseItem.getItem();
                    context.getLogger().info("Cosmos DB read Document: " + item);                 
                    context.getLogger().info("Cosmos DB read Document: " + item);
                    String updateid = item.id();
                    CosmosDBDocument updateDocument =
                            new CosmosDBDocument(updateid, item.fileName(), status, item.pageNumber());

                    container.replaceItem(updateDocument, item.id(), new PartitionKey(item.id()), null)
                            .subscribe(response -> {
                                context.getLogger().info(
                                        "Cosmos DB update Status: " + id + ":" + updateDocument);
                                context.getLogger().fine("Cosmos DB Update Response Code : "
                                        + response.getStatusCode());
                            }, error -> {
                                context.getLogger()
                                        .severe("Cosmos DB update Failed: " + error.getMessage());
                            }, () -> {
                                context.getLogger().fine("Cosmos DB update Completed");
                            });
                });
    }

    public List<CosmosDBDocument> getAllDocuments() {
        List<CosmosDBDocument> documents = new ArrayList<>();
        String query = "SELECT * FROM c";
        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
        options.setQueryMetricsEnabled(true);
        SqlQuerySpec querySpec = new SqlQuerySpec(query);
        FeedResponse<CosmosDBDocument> queryResults = container
                .queryItems(querySpec, options, CosmosDBDocument.class).byPage().blockFirst();
        if (queryResults == null) {
            return documents;
        } else {
            for (CosmosDBDocument document : queryResults.getResults()) {
                documents.add(document);
            }
            return documents;
        }
    }
}
