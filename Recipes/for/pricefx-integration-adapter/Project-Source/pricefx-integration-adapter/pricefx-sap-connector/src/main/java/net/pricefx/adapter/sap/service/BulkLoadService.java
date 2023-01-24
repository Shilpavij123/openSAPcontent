package net.pricefx.adapter.sap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import net.pricefx.connector.common.connection.PFXOperationClient;
import net.pricefx.connector.common.operation.GenericBulkLoader;
import net.pricefx.connector.common.util.IPFXExtensionType;
import net.pricefx.connector.common.util.PFXTypeCode;

public class BulkLoadService extends AbstractJsonRequestService {

    private final PFXTypeCode typeCode;
    private final IPFXExtensionType extensionType;
    private final String tableName;
    private final boolean validation;

    public BulkLoadService(PFXOperationClient pfxClient, PFXTypeCode typeCode, IPFXExtensionType extensionType, String tableName, boolean validation) {
        super(pfxClient);
        this.typeCode = typeCode;
        this.extensionType = extensionType;
        this.tableName = tableName;
        this.validation = validation;
    }

    @Override
    protected JsonNode execute(JsonNode request) {
        return new TextNode(
                new GenericBulkLoader(getPfxClient(), typeCode, extensionType, tableName).bulkLoad(request, validation));
    }
}
