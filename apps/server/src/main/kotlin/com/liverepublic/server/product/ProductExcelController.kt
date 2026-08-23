package com.liverepublic.server.product

import com.liverepublic.server.auth.AuthUser
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class ExcelUploadErrorResponse(
    val message: String,
    val errors: List<ExcelRowError>,
)

@RestController
@RequestMapping("/api/products/excel")
class ProductExcelController(private val productExcelService: ProductExcelService) {

    private val xlsxMediaType =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    @GetMapping("/template")
    fun template(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(xlsxMediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"live-republic-products.xlsx\"")
            .body(productExcelService.buildTemplate())

    @PostMapping
    fun upload(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<ExcelUploadSummary> {
        val summary = file.inputStream.use { productExcelService.upload(user.id, it) }
        return ResponseEntity.status(HttpStatus.CREATED).body(summary)
    }

    /** 검증 실패는 행별 오류 목록과 함께 400으로 응답한다. 부분 등록은 없다. */
    @ExceptionHandler(ExcelValidationException::class)
    fun handleValidation(e: ExcelValidationException): ResponseEntity<ExcelUploadErrorResponse> =
        ResponseEntity.badRequest().body(
            ExcelUploadErrorResponse(
                message = "등록되지 않았습니다. ${e.errors.size}개 행을 수정한 뒤 다시 업로드하세요.",
                errors = e.errors,
            ),
        )
}
