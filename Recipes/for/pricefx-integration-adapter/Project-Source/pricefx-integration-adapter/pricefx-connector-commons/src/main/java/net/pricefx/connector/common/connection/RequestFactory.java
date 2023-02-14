package net.pricefx.connector.common.connection;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.smartgwt.client.types.OperatorId;
import net.pricefx.connector.common.util.*;
import net.pricefx.connector.common.validation.RequestValidationException;
import net.pricefx.pckg.client.okhttp.PfxCommonService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.smartgwt.client.types.OperatorId.AND;
import static com.smartgwt.client.types.OperatorId.EQUALS;
import static net.pricefx.connector.common.util.PFXConstants.*;
import static net.pricefx.connector.common.util.PFXLookupTableType.LOWERBOUND;
import static net.pricefx.connector.common.util.PFXLookupTableType.UPPERBOUND;
import static net.pricefx.connector.common.util.PFXTypeCode.LOOKUPTABLE;
import static net.pricefx.connector.common.util.PFXTypeCode.QUOTE;
import static net.pricefx.pckg.client.okhttp.PfxCommonService.buildSimpleCriterion;

public class RequestFactory {
    private static final String OPTIONS = "options";
    private static final String FILTER_CRITERIA = "filterCriteria";
    private static final int MAX_HEADER_LENGTH = 1020;
    private static final int MAX_KEYS = 4;

    private RequestFactory() {
    }

    public static List<ObjectNode> buildAssignRoleRequest(List<String> existingRoles, List<String> newRoles, String user) {
        List<String> originalExistingRoles = ImmutableList.copyOf(existingRoles);

        existingRoles.removeAll(newRoles);
        List<ObjectNode> requests = buildAssignRoleRequest(existingRoles, false, user);

        newRoles.removeAll(originalExistingRoles);
        requests.addAll(buildAssignRoleRequest(newRoles, true, user));

        return requests;
    }

    public static List<ObjectNode> buildAssignRoleRequest(List<String> roles, boolean assign, String user) {
        if (CollectionUtils.isEmpty(roles)) {
            return new ArrayList<>();
        }

        return roles.stream().map((String role) -> ((ObjectNode) new ObjectNode(JsonNodeFactory.instance)
                .put("assign", assign).put(FIELD_UNIQUENAME, role)
                .set("users", new ArrayNode(JsonNodeFactory.instance).add(user)))).collect(Collectors.toList());
    }

    public static ObjectNode buildDeleteRequest(PFXTypeCode typeCode, ObjectNode filter, IPFXExtensionType extensionType) {
        if (typeCode.isExtension()) {
            ObjectNode criterion = PfxCommonService.buildSimpleCriterion(FIELD_NAME, OperatorId.EQUALS.getValue(), extensionType.getTable());

            ObjectNode rootNode = new ObjectNode(JsonNodeFactory.instance);
            rootNode.set(FILTER_CRITERIA, RequestUtil.createSimpleFetchRequest(AND.getValue(), ImmutableList.of(
                    (ObjectNode) filter.get(FILTER_CRITERIA),
                    RequestUtil.createSimpleFetchRequest(AND.getValue(), ImmutableList.of(criterion)))));

            return rootNode;
        } else {
            return filter;
        }
    }

    public static ObjectNode buildBulkLoadRequest(PFXTypeCode typeCode, ObjectNode request, IPFXExtensionType extensionType) {

        if (typeCode == LOOKUPTABLE && !StringUtils.isEmpty(extensionType.getTable())) {
            if (((PFXLookupTableType) extensionType).getLookupTableType() == PFXLookupTableType.LookupTableType.RANGE) {

                if (JsonUtil.getStringArray(request.get(HEADER)).contains(FIELD_NAME)) {
                    throw new RequestValidationException("field NAME should not exist in input message.");
                }

                List<JsonNode> headers = ImmutableList.copyOf(request.get(HEADER).iterator());
                int lowerPos = Iterables.indexOf(headers, u -> LOWERBOUND.equals(u.textValue()));
                int upperPos = Iterables.indexOf(headers, u -> UPPERBOUND.equals(u.textValue()));

                ((ArrayNode) request.get(HEADER)).add(FIELD_NAME);

                for (JsonNode node : request.get(FIELD_DATA)) {
                    ((ArrayNode) node).add(JsonUtil.getValueAsText(node.get(lowerPos)) + "-" +
                            JsonUtil.getValueAsText(node.get(upperPos)));
                }
            }

            //add lookup table id
            ((ArrayNode) request.get(HEADER)).add("lookupTable");
            for (JsonNode node : request.get(FIELD_DATA)) {
                ((ArrayNode) node).add(extensionType.getTable());
            }
        } else if (typeCode.isExtension()) {
            ((ArrayNode) request.get(HEADER)).add(FIELD_NAME);
            request.get(FIELD_DATA).forEach((JsonNode row) -> ((ArrayNode) row).add(extensionType.getTable()));

            ObjectNode options = new ObjectNode(JsonNodeFactory.instance);
            addJoinFieldsLengthOptions(options, extensionType);
            addJoinFieldsOptions(options, extensionType);
            request.set(OPTIONS, options);

        } else if (typeCode == PFXTypeCode.DATASOURCE) {
            request.set(OPTIONS, new ObjectNode(JsonNodeFactory.instance).put("direct2ds", true));
        }

        return request;
    }

    private static void addJoinFieldsLengthOptions(ObjectNode options, IPFXExtensionType extensionType) {
        if (extensionType.getBusinessKeys() != null && extensionType.getBusinessKeys().size() >= MAX_KEYS) {
            int maxLength = MAX_HEADER_LENGTH / (extensionType.getBusinessKeys().size() + 1);

            options.putArray("maxJoinFieldsLengths").add(
                    new ObjectNode(JsonNodeFactory.instance).put("joinField", FIELD_NAME)
                            .put("maxLength", maxLength));

            extensionType.getBusinessKeys().forEach(header ->
                    ((ArrayNode) options.get("maxJoinFieldsLengths")).add(
                            new ObjectNode(JsonNodeFactory.instance).put("joinField", header).put("maxLength", maxLength))
            );
        }
    }

    private static void addJoinFieldsOptions(ObjectNode options, IPFXExtensionType extensionType) {

        if (!CollectionUtils.isEmpty(extensionType.getBusinessKeys())) {
            ArrayNode businessKeys = new ArrayNode(JsonNodeFactory.instance);
            extensionType.getBusinessKeys().forEach(businessKeys::add);
            businessKeys.add(FIELD_NAME);
            options.set("joinFields", businessKeys);
        }
    }

    public static ObjectNode buildCreateRequest(PFXTypeCode typeCode, JsonNode request) {
        if (typeCode == QUOTE) {
            request = buildCreateQuoteRequest(request);
        }

        return (ObjectNode) request;
    }

    private static ObjectNode buildCreateQuoteRequest(JsonNode request) {
        if (!JsonUtil.isObjectNode(request)) return null;

        ArrayNode folders = (ArrayNode) request.get(FIELD_FOLDERS);
        ((ObjectNode) request).set(FIELD_FOLDERS, new ArrayNode(JsonNodeFactory.instance));
        request = new ObjectNode(JsonNodeFactory.instance).set(FIELD_QUOTE, request);

        if (folders != null) {
            for (JsonNode folder : folders) {
                if (folder.isTextual()) {
                    String lineId = JsonUtil.getRandomId(folder.textValue());
                    ObjectNode folderNode = new ObjectNode(JsonNodeFactory.instance).put(FIELD_LABEL, folder.textValue())
                            .put("folder", true)
                            .put(FIELD_LINEID, lineId);
                    ((ArrayNode) request.get(FIELD_QUOTE).get(FIELD_FOLDERS)).add(folderNode);
                }
            }
        }

        moveValueObject((ArrayNode) request.get(FIELD_QUOTE).get(FIELD_INPUTS));

        ArrayNode lineItems = (ArrayNode) request.get(FIELD_QUOTE).get(FIELD_LINEITEMS);
        if (lineItems != null) {
            lineItems.forEach((JsonNode lineItem) -> {
                if (JsonUtil.isObjectNode(lineItem)) {
                    moveValueObject((ArrayNode) lineItem.get(FIELD_INPUTS));
                }
            });
        }

        return (ObjectNode) request;
    }

    private static void moveValueObject(ArrayNode inputs) {
        if (inputs != null) {
            inputs.forEach((JsonNode input) -> {

                if (JsonUtil.isObjectNode(input) &&
                        JsonUtil.isObjectNode(input.get(FIELD_VALUEOBJECT)) && input.get(FIELD_VALUEOBJECT).size() > 0) {
                    if (!StringUtils.isEmpty(JsonUtil.getValueAsText(input.get(FIELD_VALUE)))) {
                        throw new RequestValidationException("Either value or valueObject is allowed");
                    }

                    ((ObjectNode) input).set(FIELD_VALUE, input.get(FIELD_VALUEOBJECT));
                    ((ObjectNode) input).remove(FIELD_VALUEOBJECT);
                }

            });
        }
    }

    public static ObjectNode buildFetchMetadataRequest(PFXTypeCode typeCode, IPFXExtensionType extensionType, String uniqueKey) {

        ObjectNode criterion = null;


        if (typeCode == PFXTypeCode.PRICELISTITEM) {
            criterion = buildSimpleCriterion(FIELD_PLI_PRICELISTID, EQUALS.getValue(), uniqueKey);
        } else if (typeCode == PFXTypeCode.PRICEGRIDITEM) {
            criterion = buildSimpleCriterion(FIELD_PGI_PRICEGRIDID, EQUALS.getValue(), uniqueKey);
        } else if (typeCode == PFXTypeCode.MANUALPRICELISTITEM) {
            criterion = buildSimpleCriterion(FIELD_PLI_PRICELISTID, EQUALS.getValue(), uniqueKey);
        } else if (!StringUtils.isEmpty(uniqueKey) && typeCode == PFXTypeCode.ROLE) {
            criterion = buildSimpleCriterion("module", EQUALS.getValue(), uniqueKey);
        } else if (extensionType != null && extensionType.getTypeCode() != null && !StringUtils.isEmpty(extensionType.getTable())) {
            if (typeCode == LOOKUPTABLE) {
                criterion = buildSimpleCriterion("lookupTableId", EQUALS.getValue(), extensionType.getTable());
            } else {
                criterion = buildSimpleCriterion(FIELD_NAME, EQUALS.getValue(), extensionType.getTable());
            }
        }

        return criterion == null ? null : RequestUtil.createSimpleFetchRequest(criterion);

    }

    public static ArrayNode buildUpsertRequest(PFXTypeCode typeCode, IPFXExtensionType extensionType, JsonNode request) {
        if (request == null || (!request.isObject() && !request.isArray()) ||
                (JsonUtil.isArrayNode(request) && request.size() == 0)) {
            return null;
        }

        if (JsonUtil.isObjectNode(request)) {
            request = JsonUtil.createArrayNode(request);
        }

        if (typeCode == PFXTypeCode.USER) {
            request.forEach((JsonNode node) -> {
                addUserFilterCriteria(node, "productFilterCriteria");
                addUserFilterCriteria(node, "customerFilterCriteria");
            });
        } else if (typeCode.isExtension() && extensionType != null) {
            request.forEach((JsonNode node) -> addExtensionName(node, extensionType));
        }

        return (ArrayNode) request;
    }

    private static void addUserFilterCriteria(JsonNode request, String fieldName) {
        JsonNode criteriaNode = request.get(fieldName);
        if (criteriaNode != null && criteriaNode.isObject()) {
            RequestUtil.addAdvancedCriteria((ObjectNode) criteriaNode);
            ((ObjectNode) request).put(fieldName, criteriaNode.toString());
        }
    }

    private static void addExtensionName(JsonNode node, IPFXExtensionType extensionType) {
        if (JsonUtil.isObjectNode(node) && !StringUtils.isEmpty(extensionType.getTable())) {
            ((ObjectNode) node).put(FIELD_NAME, extensionType.getTable());
        }
    }
}