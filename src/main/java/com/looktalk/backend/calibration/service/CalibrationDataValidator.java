package com.looktalk.backend.calibration.service;

import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 10.1 - calibration_data v1 계약 validation.
// Backend는 Homography를 계산/재학습하지 않지만 malformed payload와 raw biometric 데이터 저장은 막아야 한다.
// calibrationData는 Controller/DTO에서 JsonNode(tree model)로 받으므로 Jackson의 전역 POJO
// unknown-field 정책(다른 API의 lenient 기본값)과 무관하게, 이 Validator가 허용된 key/값만 명시적으로
// 통과시킨다. 허용되지 않은 key가 하나라도 있으면 즉시 거부해 raw landmark/head-pose 등 계약 외 필드가
// silently 저장되지 않게 한다.
@Component
public class CalibrationDataValidator {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "mappingType", "coordinateSpace", "homography", "mirrorX",
            "mirrorStrategy", "grid", "calibratedViewport", "reprojectionRmseNormalized");

    // reprojectionRmseNormalized만 optional이고 나머지는 전부 필수다.
    private static final Set<String> REQUIRED_TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "mappingType", "coordinateSpace", "homography", "mirrorX",
            "mirrorStrategy", "grid", "calibratedViewport");

    private static final Set<String> GRID_FIELDS = Set.of("rows", "cols", "margin");
    private static final Set<String> VIEWPORT_FIELDS = Set.of("widthPx", "heightPx", "aspectRatio", "orientation");

    private static final Set<String> MIRROR_STRATEGIES =
            Set.of("PRE_INFERENCE_FRAME_FLIP", "POST_INFERENCE_LANDMARK_FLIP");
    private static final Set<String> ORIENTATIONS = Set.of("portrait", "landscape");

    // JSON numeric 0.08 계약을 double exact binary 비교로 취약하게 검증하지 않기 위한 epsilon.
    private static final double MARGIN_EPSILON = 1e-9;
    private static final double EXPECTED_MARGIN = 0.08;

    public void validate(JsonNode data) {
        requireObject(data);
        requireExactFields(data, TOP_LEVEL_FIELDS, REQUIRED_TOP_LEVEL_FIELDS);

        requireNumberEquals(data, "schemaVersion", 1);
        requireTextEquals(data, "mappingType", "RAW_HOMOGRAPHY");
        requireTextEquals(data, "coordinateSpace", "NORMALIZED_VIEWPORT");
        validateHomography(data.get("homography"));
        requireBooleanTrue(data, "mirrorX");
        requireTextIn(data, "mirrorStrategy", MIRROR_STRATEGIES);
        validateGrid(data.get("grid"));
        validateCalibratedViewport(data.get("calibratedViewport"));
        validateOptionalRmse(data.get("reprojectionRmseNormalized"));
    }

    private void validateHomography(JsonNode homography) {
        if (homography == null || !homography.isArray() || homography.size() != 3) {
            throw invalid();
        }
        for (JsonNode row : homography) {
            if (!row.isArray() || row.size() != 3) {
                throw invalid();
            }
            for (JsonNode value : row) {
                requireFiniteNumber(value);
            }
        }
    }

    private void validateGrid(JsonNode grid) {
        requireObject(grid);
        requireExactFields(grid, GRID_FIELDS, GRID_FIELDS);

        requireNumberEquals(grid, "rows", 4);
        requireNumberEquals(grid, "cols", 4);

        JsonNode margin = grid.get("margin");
        if (margin == null || !margin.isNumber() || Math.abs(margin.asDouble() - EXPECTED_MARGIN) > MARGIN_EPSILON) {
            throw invalid();
        }
    }

    private void validateCalibratedViewport(JsonNode viewport) {
        requireObject(viewport);
        requireExactFields(viewport, VIEWPORT_FIELDS, VIEWPORT_FIELDS);

        requirePositiveNumber(viewport, "widthPx");
        requirePositiveNumber(viewport, "heightPx");
        requirePositiveNumber(viewport, "aspectRatio");
        requireTextIn(viewport, "orientation", ORIENTATIONS);
    }

    private void validateOptionalRmse(JsonNode rmse) {
        if (rmse == null || rmse.isNull()) {
            return;
        }
        requireFiniteNumber(rmse);
        if (rmse.asDouble() < 0) {
            throw invalid();
        }
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
    }

    private void requireExactFields(JsonNode node, Set<String> allowed, Set<String> required) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid();
            }
        }
        for (String requiredField : required) {
            if (!node.has(requiredField) || node.get(requiredField).isNull()) {
                throw invalid();
            }
        }
    }

    // schemaVersion/grid.rows/grid.cols처럼 정수 의미를 갖는 필드를 검증한다. JSON은 1과 1.0을 값으로는
    // 동일하게 취급하므로 isIntegralNumber() 같은 lexical 표현 기반 체크 대신 수치 비교를 사용한다.
    private void requireNumberEquals(JsonNode node, String field, double expected) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.asDouble() != expected) {
            throw invalid();
        }
    }

    private void requireTextEquals(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || !expected.equals(value.asString())) {
            throw invalid();
        }
    }

    private void requireTextIn(JsonNode node, String field, Set<String> allowedValues) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || !allowedValues.contains(value.asString())) {
            throw invalid();
        }
    }

    private void requireBooleanTrue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean() || !value.asBoolean()) {
            throw invalid();
        }
    }

    private void requirePositiveNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        requireFiniteNumber(value);
        if (value.asDouble() <= 0) {
            throw invalid();
        }
    }

    private void requireFiniteNumber(JsonNode value) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.CALIBRATION_INVALID_PAYLOAD);
    }
}
