package com.liverepublic.server.product

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 행 단위 검증 오류. row는 Excel의 실제 행 번호(헤더 포함, 1부터). */
data class ExcelRowError(val row: Int, val message: String)

data class ExcelUploadSummary(
    val createdProducts: Int,
    val createdSkus: Int,
)

class ExcelValidationException(val errors: List<ExcelRowError>) :
    RuntimeException("Excel 검증 실패 ${errors.size}건")

private data class ParsedRow(
    val rowNumber: Int,
    val productName: String,
    val price: Int,
    val description: String?,
    // (그룹명 → 옵션명) 열 위치 순서 유지
    val options: List<Pair<String, String>>,
    val onHand: Int,
)

private data class ParseResult(
    val rows: List<ParsedRow>,
    val errors: List<ExcelRowError>,
)

/**
 * Excel 상품 일괄등록. 정책(Issue #14): 모든 행이 검증을 통과할 때만 등록한다 —
 * 부분 등록은 없고, 실패 시 발견된 모든 행 오류를 한 번에 반환한다.
 *
 * 양식: 한 행 = 한 SKU. 같은 상품명의 행은 같은 상품으로 묶이며,
 * 가격·설명·옵션그룹 구성은 첫 행과 같아야 한다. 파일에 있는 조합만 SKU가 된다.
 */
@Service
class ProductExcelService(private val productService: ProductService) {

    companion object {
        val HEADERS = listOf(
            "상품명", "가격", "설명",
            "옵션그룹1", "옵션1", "옵션그룹2", "옵션2", "옵션그룹3", "옵션3",
            "재고",
        )
        const val MAX_ROWS = 1000
        const val SHEET_NAME = "상품"
    }

    /** 작성 양식 시트와 예시 시트를 담은 템플릿을 만든다. */
    fun buildTemplate(): ByteArray {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(SHEET_NAME)
            val header = sheet.createRow(0)
            HEADERS.forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }

            val example = workbook.createSheet("작성 예시 (업로드되지 않음)")
            val rows = listOf(
                HEADERS,
                listOf("티셔츠", "15000", "부드러운 면", "색상", "빨강", "사이즈", "M", "", "", "10"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "빨강", "사이즈", "L", "", "", "5"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "파랑", "사이즈", "M", "", "", "0"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "파랑", "사이즈", "L", "", "", "8"),
                listOf("양말", "3000", "", "", "", "", "", "", "", "30"),
            )
            rows.forEachIndexed { ri, values ->
                val row = example.createRow(ri)
                values.forEachIndexed { ci, value -> row.createCell(ci).setCellValue(value) }
            }

            val out = ByteArrayOutputStream()
            workbook.write(out)
            return out.toByteArray()
        }
    }

    /** 전체 검증 통과 시에만 한 Transaction으로 등록한다. */
    @Transactional
    fun upload(userId: Long, input: InputStream): ExcelUploadSummary {
        val parsed = parse(input)
        val errors = parsed.errors.toMutableList()
        val rows = parsed.rows

        if (rows.isEmpty() && errors.isEmpty()) {
            throw ExcelValidationException(listOf(ExcelRowError(2, "등록할 행이 없습니다. '$SHEET_NAME' 시트에 작성하세요.")))
        }

        // 상품 단위 일관성 검증 — 필드 오류가 없는 행들만 대상으로, 발견된 오류를 모두 누적한다.
        val byProduct = rows.groupBy { it.productName }
        val validProducts = mutableListOf<Triple<String, ParsedRow, List<ParsedRow>>>()
        byProduct.forEach { (name, productRows) ->
            val first = productRows.first()
            var productOk = true
            productRows.forEach { row ->
                if (row.price != first.price) {
                    errors += ExcelRowError(row.rowNumber, "[$name] 가격이 첫 행(${first.price})과 다릅니다.")
                    productOk = false
                }
                if (row.description != first.description) {
                    errors += ExcelRowError(row.rowNumber, "[$name] 설명이 첫 행과 다릅니다.")
                    productOk = false
                }
                // 그룹 구성은 열 위치 기준으로 비교한다.
                if (row.options.map { it.first } != first.options.map { it.first }) {
                    errors += ExcelRowError(row.rowNumber, "[$name] 옵션그룹 구성이 첫 행과 다릅니다.")
                    productOk = false
                }
            }
            // 같은 옵션 조합 중복 검사 (열 위치 순서)
            val seen = mutableMapOf<List<String>, Int>()
            productRows.forEach { row ->
                val combo = row.options.map { it.second }
                seen.put(combo, row.rowNumber)?.let { firstRow ->
                    errors += ExcelRowError(row.rowNumber, "[$name] ${firstRow}행과 같은 옵션 조합입니다.")
                    productOk = false
                }
            }
            if (productOk) validProducts += Triple(name, first, productRows)
        }
        if (errors.isNotEmpty()) throw ExcelValidationException(errors.sortedBy { it.row })

        // 등록: 한 행 = 한 SKU — 파일에 있는 조합만 생성한다 (Cartesian 아님).
        // 옵션 제약은 행 단위로 이미 검증했으므로 여기서 실패하면 안 되지만,
        // 만약 실패하면 첫 행 번호와 함께 전체를 되돌린다.
        val shopId = productService.ownerShopId(userId)
        var createdProducts = 0
        var createdSkus = 0
        validProducts.forEach { (name, first, productRows) ->
            // Option 값은 그룹명이 아니라 열 위치(index) 기준으로 수집한다.
            val optionGroups = first.options.mapIndexed { gi, (groupName, _) ->
                NewOptionGroup(
                    name = groupName,
                    options = productRows.map { row -> row.options[gi].second }.distinct(),
                )
            }
            val skus = productRows.map { row ->
                NewSkuSpec(optionNames = row.options.map { it.second }, onHand = row.onHand)
            }
            try {
                productService.createProductWithSkus(
                    shopId = shopId,
                    name = name,
                    price = first.price,
                    description = first.description,
                    optionGroups = optionGroups,
                    skus = skus,
                )
            } catch (e: org.springframework.web.server.ResponseStatusException) {
                throw ExcelValidationException(
                    listOf(ExcelRowError(first.rowNumber, "[$name] ${e.reason ?: "등록할 수 없습니다."}")),
                )
            }
            createdProducts += 1
            createdSkus += skus.size
        }
        return ExcelUploadSummary(createdProducts = createdProducts, createdSkus = createdSkus)
    }

    /**
     * 필드 수준 검증을 행 번호와 함께 모두 수집한다. 즉시 던지는 예외는
     * 열 해석 자체가 불가능한 구조 문제(파일·시트·헤더·행 수)뿐이다.
     */
    private fun parse(input: InputStream): ParseResult {
        val formatter = DataFormatter()
        val workbook = try {
            WorkbookFactory.create(input)
        } catch (e: Exception) {
            throw ExcelValidationException(listOf(ExcelRowError(1, "Excel(.xlsx) 파일을 읽을 수 없습니다.")))
        }
        workbook.use {
            if (it.numberOfSheets == 0) {
                throw ExcelValidationException(listOf(ExcelRowError(1, "시트가 없는 파일입니다. 템플릿을 사용하세요.")))
            }
            val sheet = it.getSheet(SHEET_NAME)
                ?: throw ExcelValidationException(
                    listOf(ExcelRowError(1, "'$SHEET_NAME' 시트가 없습니다. 템플릿을 내려받아 작성하세요.")),
                )
            val headerRow = sheet.getRow(0)
            val actualHeaders = (0 until HEADERS.size).map { c -> formatter.formatCellValue(headerRow?.getCell(c)).trim() }
            if (actualHeaders != HEADERS) {
                throw ExcelValidationException(
                    listOf(ExcelRowError(1, "1행 헤더가 템플릿과 다릅니다. 열 순서를 바꾸지 말고 템플릿을 사용하세요.")),
                )
            }
            if (sheet.lastRowNum > MAX_ROWS) {
                throw ExcelValidationException(
                    listOf(ExcelRowError(1, "한 번에 최대 ${MAX_ROWS}행까지 업로드할 수 있습니다.")),
                )
            }

            val errors = mutableListOf<ExcelRowError>()
            val rows = mutableListOf<ParsedRow>()
            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val cells = (0 until HEADERS.size).map { c -> formatter.formatCellValue(row.getCell(c)).trim() }
                if (cells.all { c -> c.isEmpty() }) continue
                val rowNumber = rowIndex + 1
                val rowErrors = mutableListOf<String>()

                val name = cells[0]
                if (name.isEmpty()) rowErrors += "상품명이 비어 있습니다."
                if (name.length > 200) rowErrors += "상품명은 200자 이하여야 합니다."
                if (cells[2].length > 2000) rowErrors += "설명은 2,000자 이하여야 합니다."

                val price = cells[1].replace(",", "").toIntOrNull()
                if (price == null || price < 0) rowErrors += "가격은 0 이상의 숫자여야 합니다. (입력: '${cells[1]}')"

                val onHandText = cells[9]
                val onHand = if (onHandText.isEmpty()) 0 else onHandText.replace(",", "").toIntOrNull() ?: -1
                if (onHand < 0) rowErrors += "재고는 0 이상의 숫자여야 합니다. (입력: '$onHandText')"

                val options = mutableListOf<Pair<String, String>>()
                for (g in 0 until 3) {
                    val groupName = cells[3 + g * 2]
                    val optionName = cells[4 + g * 2]
                    if (groupName.isEmpty() && optionName.isEmpty()) continue
                    if (groupName.isEmpty() || optionName.isEmpty()) {
                        rowErrors += "옵션그룹${g + 1}과 옵션${g + 1}은 함께 입력해야 합니다."
                        continue
                    }
                    if (groupName.length > 50) rowErrors += "옵션그룹${g + 1} 이름은 50자 이하여야 합니다."
                    if (groupName.contains('/') || groupName.contains('=')) {
                        rowErrors += "옵션그룹${g + 1} 이름에는 '/'와 '='를 사용할 수 없습니다."
                    }
                    if (optionName.length > 50) rowErrors += "옵션${g + 1} 이름은 50자 이하여야 합니다."
                    if (optionName.contains('/') || optionName.contains(',') || optionName.contains('=')) {
                        rowErrors += "옵션${g + 1} 이름에는 '/', ',', '='를 사용할 수 없습니다."
                    }
                    options += groupName to optionName
                }
                // 같은 행에서 옵션그룹 이름이 중복되면 조합을 구분할 수 없다.
                val groupNames = options.map { it.first }
                if (groupNames.toSet().size != groupNames.size) {
                    rowErrors += "옵션그룹 이름이 중복됩니다. 그룹마다 다른 이름을 사용하세요."
                }

                if (rowErrors.isNotEmpty()) {
                    rowErrors.forEach { message -> errors += ExcelRowError(rowNumber, message) }
                    continue
                }
                rows += ParsedRow(
                    rowNumber = rowNumber,
                    productName = name,
                    price = price!!,
                    description = cells[2].ifEmpty { null },
                    options = options,
                    onHand = onHand,
                )
            }
            return ParseResult(rows = rows, errors = errors)
        }
    }
}
