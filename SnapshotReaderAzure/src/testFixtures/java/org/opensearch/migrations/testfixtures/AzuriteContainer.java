package org.opensearch.migrations.testfixtures;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class AzuriteContainer extends GenericContainer<AzuriteContainer> {

    // Azurite's published development credentials, not secrets.
    public static final String ACCOUNT_NAME = "devstoreaccount1";
    public static final String ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final int BLOB_PORT = 10000;

    public AzuriteContainer() {
        // Older Azurite releases reject the API version azure-storage-blob 12.35.1 sends.
        super("mcr.microsoft.com/azure-storage/azurite:3.37.0");
        withExposedPorts(BLOB_PORT);
        withCommand("azurite-blob", "--blobHost", "0.0.0.0");
        // Azurite answers unauthenticated probes with 403, so wait on the startup log line.
        waitingFor(Wait.forLogMessage(".*successfully listens.*", 1));
    }

    public String getEndpoint() {
        return "http://" + getHost() + ":" + getMappedPort(BLOB_PORT);
    }

    public BlobContainerClient createBlobContainer(String containerName) {
        BlobContainerClient container = new BlobServiceClientBuilder()
            .endpoint(getEndpoint() + "/" + ACCOUNT_NAME)
            .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
            .buildClient()
            .getBlobContainerClient(containerName);
        container.createIfNotExists();
        return container;
    }
}
