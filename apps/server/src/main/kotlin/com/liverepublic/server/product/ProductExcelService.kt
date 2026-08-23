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
    // (그룹명 → 옵션명) 순서 유지
    val options: List<Pair<String, String>>,
    val onHand: Int,
)

/**
 * Excel 상품 일괄등록. 정책(Issue #14): 모든 행이 검증을 통과할 때만 등록한다 —
 * 부분 등록은 없고, 실패 시 행별 오류 목록을 반환한다.
 *
 * 양식: 한 행 = 한 SKU. 같은 상품명의 행은 같은 상품으로 묶이며,
 * 가격·설명·옵션그룹 구성은 첫 행과 같아야 한다.
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
    }

    /** 작성 양식 시트와 예시 시트를 담은 템플릿을 만든다. */
    fun buildTemplate(): ByteArray {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("상품")
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
        val rows = parse(input)
        val errors = mutableListOf<ExcelRowError>()

        if (rows.isEmpty()) {
            throw ExcelValidationException(listOf(ExcelRowError(2, "등록할 행이 없습니다. '상품' 시트에 작성하세요.")))
        }

        // 상품 단위로 묶어 일관성 검증
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
                if (row.options.map { it.first } != first.options.map { it.first }) {
                    errors += ExcelRowError(row.rowNumber, "[$name] 옵션그룹 구성이 첫 행과 다릅니다.")
                    productOk = false
                }
            }
            // 같은 옵션 조합 중복 검사
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

        // 등록: 상품 구조 생성(조합 SKU 자동 생성) 후 행별 재고 설정
        var createdProducts = 0
        var createdSkus = 0
        validProducts.forEach { (name, first, productRows) ->
            val optionGroups = first.options.map { (groupName, _) ->
                NewOptionGroup(
                    name = groupName,
                    options = productRows
                        .map { row -> row.options.first { it.first == groupName }.second }
                        .distinct(),
                )
            }
            val productId = try {
                productService.createProduct(
                    userId = userId,
                    name = name,
                    price = first.price,
                    description = first.description,
                    optionGroups = optionGroups,
                )
            } catch (e: org.springframework.web.server.ResponseStatusException) {
                throw ExcelValidationException(
                    listOf(ExcelRowError(first.rowNumber, "[$name] ${e.reason ?: "등록할 수 없습니다."}")),
                )
            }
            val skus = productService.listSkus(productId)
            createdProducts += 1
            createdSkus += skus.size
            productRows.forEach { row ->
                val label = if (row.options.isEmpty()) "기본" else row.options.joinToString(" / ") { it.second }
                val sku = skus.firstOrNull { it.optionLabel == label }
                    ?: throw ExcelValidationException(
                        listOf(ExcelRowError(row.rowNumber, "[$name] 옵션 조합($label)을 SKU로 만들 수 없습니다.")),
                    )
                if (row.onHand > 0) {
                    productService.updateOnHand(userId, productId, sku.id!!, row.onHand)
                }
            }
        }
        return ExcelUploadSummary(createdProducts = createdProducts, createdSkus = createdSkus)
    }

    private fun parse(input: InputStream): List<ParsedRow> {
        val formatter = DataFormatter()
        val workbook = try {
            WorkbookFactory.create(input)
        } catch (e: Exception) {
            throw ExcelValidationException(listOf(ExcelRowError(1, "Excel(.xlsx) 파일을 읽을 수 없습니다.")))
        }
        workbook.use {
            val sheet = it.getSheetAt(0)
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

                val name = cells[0]
                if (name.isEmpty()) {
                    errors += ExcelRowError(rowNumber, "상품명이 비어 있습니다.")
                    continue
                }
                val price = cells[1].replace(",", "").toIntOrNull()
                if (price == null || price < 0) {
                    errors += ExcelRowError(rowNumber, "가격은 0 이상의 숫자여야 합니다. (입력: '${cells[1]}')")
                    continue
                }
                val onHand = if (cells[9].isEmpty()) 0 else cells[9].replace(",", "").toIntOrNull() ?: -1
                if (onHand < 0) {
                    errors += ExcelRowError(rowNumber, "재고는 0 이상의 숫자여야 합니다. (입력: '${cells[9]}')")
                    continue
                }

                val options = mutableListOf<Pair<String, String>>()
                var optionError = false
                for (g in 0 until 3) {
                    val groupName = cells[3 + g * 2]
                    val optionName = cells[4 + g * 2]
                    if (groupName.isEmpty() && optionName.isEmpty()) continue
                    if (groupName.isEmpty() || optionName.isEmpty()) {
                        errors += ExcelRowError(rowNumber, "옵션그룹${g + 1}과 옵션${g + 1}은 함께 입력해야 합니다.")
                        optionError = true
                        break
                    }
                    options += groupName to optionName
                }
                if (optionError) continue

                rows += ParsedRow(
                    rowNumber = rowNumber,
                    productName = name,
                    price = price,
                    description = cells[2].ifEmpty { null },
                    options = options,
                    onHand = onHand,
                )
            }
            if (errors.isNotEmpty()) throw ExcelValidationException(errors)
            return rows
        }
    }
}
