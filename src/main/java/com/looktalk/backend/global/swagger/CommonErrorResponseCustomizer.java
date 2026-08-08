package com.looktalk.backend.global.swagger;

import com.looktalk.backend.global.exception.ValidationErrorData;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Map;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

// docs/LookTalk_Backend_API_Spec_v2.1.md 11, 12, 14, 65. 공통 Response/Error 계약을 /v3/api-docs에도 정확히 반영한다.
// 실제 런타임 응답(ApiResponse, GlobalExceptionHandler)은 전혀 건드리지 않고 OpenAPI 문서 schema만 보정한다.
// 컨트롤러마다 @Content/@Schema를 반복 작성하지 않도록 여기 한 곳에서 전역 처리하며,
// 이후 Friend/Chat 등 신규 API도 기존과 동일하게 @Operation + @ApiResponse(responseCode, description)만
// 작성하면 이 Customizer가 자동으로 schema를 보정해준다.
//
// 1) OperationCustomizer#customize - @Valid @RequestBody를 쓰는 API의 400 응답을 다음 세 가지로 구분한다.
//    a) 문서화된 400이 전혀 없으면: Bean Validation만 가능하므로 ValidationErrorData 단독 400을 추가한다.
//    b) 이미 업무 규칙 400(예: E2EE_INVALID_PAYLOAD)이 문서화되어 있으면: 같은 HTTP 400으로 Bean Validation
//       실패도 반환될 수 있으므로 ApiResponseVoid/ApiResponseValidationErrorData를 oneOf로 함께 표현한다.
//    두 경우 모두 x-looktalk-error-schema-resolved 확장 필드로 표시해 2)의 전역 보정에서 덮어쓰지 않게 한다.
//    (@Valid가 없는 API의 400은 업무 오류만 가능하므로 건드리지 않고 2)에 맡긴다.)
// 2) GlobalOpenApiCustomizer#customise - ApiResponseValidationErrorData 공통 schema를 등록하고,
//    1)에서 이미 확정하지 않은 응답 중 성공 응답과 동일한 schema로 방치된(즉 @ApiResponse에 description만
//    적고 별도 schema를 지정하지 않아 컨트롤러 반환 타입을 그대로 물려받은) 모든 4xx/5xx 응답을, 실제 런타임
//    구조(success=false, code, message, data=null)에 대응하는 공통 schema(ApiResponseVoid)로 교체한다.
@Component
public class CommonErrorResponseCustomizer implements OperationCustomizer, GlobalOpenApiCustomizer {

    private static final String GENERIC_ERROR_SCHEMA_NAME = "ApiResponseVoid";
    private static final String GENERIC_ERROR_SCHEMA_REF = "#/components/schemas/" + GENERIC_ERROR_SCHEMA_NAME;
    private static final String VALIDATION_ERROR_SCHEMA_NAME = "ApiResponseValidationErrorData";
    private static final String VALIDATION_ERROR_SCHEMA_REF = "#/components/schemas/" + VALIDATION_ERROR_SCHEMA_NAME;
    private static final String COMMON_VALIDATION_ERROR_DESCRIPTION = "COMMON_VALIDATION_ERROR - 입력값을 확인해주세요.";
    private static final String VALIDATION_ERROR_APPENDIX = " 또는 " + COMMON_VALIDATION_ERROR_DESCRIPTION;
    // 실제 런타임 오류 응답은 ResponseEntity<ApiResponse<T>>를 Jackson으로 직렬화해 application/json으로 반환하므로
    // 문서상 media type도 */*가 아니라 application/json으로 명시한다.
    private static final String MEDIA_TYPE = "application/json";
    private static final String RESOLVED_EXTENSION = "x-looktalk-error-schema-resolved";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (!hasValidRequestBody(handlerMethod)) {
            return operation;
        }

        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return operation;
        }

        ApiResponse existingBusinessError = responses.get("400");
        if (existingBusinessError == null) {
            ApiResponse validationErrorResponse = new ApiResponse();
            validationErrorResponse.setDescription(COMMON_VALIDATION_ERROR_DESCRIPTION);
            validationErrorResponse.setContent(contentWithRef(VALIDATION_ERROR_SCHEMA_REF));
            markResolved(validationErrorResponse);
            responses.addApiResponse("400", validationErrorResponse);
        } else {
            // 같은 400에서 업무 규칙 위반과 Bean Validation 실패가 모두 가능하므로 두 schema를 oneOf로 함께 문서화한다.
            existingBusinessError.setDescription(existingBusinessError.getDescription() + VALIDATION_ERROR_APPENDIX);
            existingBusinessError.setContent(oneOfContent(GENERIC_ERROR_SCHEMA_REF, VALIDATION_ERROR_SCHEMA_REF));
            markResolved(existingBusinessError);
        }

        return operation;
    }

    private void markResolved(ApiResponse response) {
        response.addExtension(RESOLVED_EXTENSION, true);
    }

    private boolean hasValidRequestBody(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)
                    && parameter.hasParameterAnnotation(Valid.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void customise(OpenAPI openApi) {
        registerValidationErrorSchema(openApi);
        fixGenericErrorSchemas(openApi);
    }

    private void registerValidationErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        if (schemas != null && schemas.containsKey(VALIDATION_ERROR_SCHEMA_NAME)) {
            return;
        }

        ResolvedSchema resolved = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ValidationErrorData.class));

        Schema<Object> wrapper = new Schema<>();
        wrapper.addType("object");
        wrapper.addProperty("success", primitiveSchema("boolean"));
        wrapper.addProperty("code", primitiveSchema("string"));
        wrapper.addProperty("message", primitiveSchema("string"));
        wrapper.addProperty("data", resolved.schema);

        openApi.getComponents().addSchemas(VALIDATION_ERROR_SCHEMA_NAME, wrapper);

        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
        }
    }

    // 문서의 OpenAPI 버전(3.1)에서는 addType()으로 채운 types 집합을 기준으로 직렬화되므로
    // 구버전 필드인 setType()이 아니라 addType()을 사용해야 실제 출력에 "type"이 반영된다.
    private Schema<Object> primitiveSchema(String type) {
        Schema<Object> schema = new Schema<>();
        schema.addType(type);
        return schema;
    }

    private void fixGenericErrorSchemas(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }

        for (PathItem pathItem : openApi.getPaths().values()) {
            for (Operation operation : pathItem.readOperations()) {
                fixOperationErrorSchemas(operation);
            }
        }
    }

    private void fixOperationErrorSchemas(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }

        String successSchemaRef = findSuccessSchemaRef(responses);

        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (isSuccessStatus(entry.getKey())) {
                continue;
            }

            ApiResponse response = entry.getValue();

            // 1)의 OperationCustomizer가 이미 확정한(단독 ValidationErrorData 또는 oneOf) 응답은 건드리지 않는다.
            if (isResolved(response)) {
                continue;
            }

            String currentRef = extractSchemaRef(response);

            // 이미 성공 응답과 다른 schema로 명시적으로 지정된 경우는 건드리지 않는다.
            if (currentRef != null && !currentRef.equals(successSchemaRef)) {
                continue;
            }

            response.setContent(contentWithRef(GENERIC_ERROR_SCHEMA_REF));
        }
    }

    private boolean isResolved(ApiResponse response) {
        return response.getExtensions() != null && response.getExtensions().containsKey(RESOLVED_EXTENSION);
    }

    private boolean isSuccessStatus(String statusCode) {
        return statusCode.startsWith("2");
    }

    private String findSuccessSchemaRef(ApiResponses responses) {
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (isSuccessStatus(entry.getKey())) {
                String ref = extractSchemaRef(entry.getValue());
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    private String extractSchemaRef(ApiResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        for (MediaType mediaType : response.getContent().values()) {
            if (mediaType.getSchema() != null) {
                return mediaType.getSchema().get$ref();
            }
        }
        return null;
    }

    private Content contentWithRef(String ref) {
        Schema<Object> refSchema = new Schema<>();
        refSchema.set$ref(ref);

        MediaType mediaType = new MediaType().schema(refSchema);
        return new Content().addMediaType(MEDIA_TYPE, mediaType);
    }

    // 같은 HTTP 400이 업무 규칙 오류(ApiResponseVoid)와 Bean Validation 오류(ApiResponseValidationErrorData)
    // 둘 다로 반환될 수 있는 API용. $ref만 담은 개별 schema를 oneOf 목록으로 묶는다.
    private Content oneOfContent(String... refs) {
        Schema<Object> oneOfSchema = new Schema<>();
        for (String ref : refs) {
            Schema<Object> refSchema = new Schema<>();
            refSchema.set$ref(ref);
            oneOfSchema.addOneOfItem(refSchema);
        }

        MediaType mediaType = new MediaType().schema(oneOfSchema);
        return new Content().addMediaType(MEDIA_TYPE, mediaType);
    }
}
