package ru.dkd.app.data.repository

import ru.dkd.app.core.network.DkdDocumentDto
import ru.dkd.app.core.network.PassportDto
import ru.dkd.app.core.network.loadDocumentsDto
import ru.dkd.app.core.network.loadPassportDto

internal data class DocumentsData(
    val hasPassport: Boolean,
    val passport: PassportDto?,
    val mandates: List<DkdDocumentDto>,
    val presidentCertificates: List<DkdDocumentDto>,
    val decrees: List<DkdDocumentDto>,
    val laws: List<DkdDocumentDto>,
    val passportLoadFailed: Boolean,
    val documentsLoadFailed: Boolean,
    val error: String?
)

internal class DocumentsRepository {
    suspend fun load(token: String): DocumentsData {
        val passportResult = loadPassportDto(token)
        val documentsResult = loadDocumentsDto(token)
        val envelope = documentsResult.data
        return DocumentsData(
            hasPassport = passportResult.data?.hasPassport == true,
            passport = passportResult.data?.passport,
            mandates = envelope?.mandates.orEmpty(),
            presidentCertificates = envelope?.presidentCertificates.orEmpty(),
            decrees = envelope?.decrees.orEmpty(),
            laws = envelope?.laws.orEmpty(),
            passportLoadFailed = passportResult.error != null || passportResult.data == null,
            documentsLoadFailed = documentsResult.error != null || envelope == null,
            error = passportResult.error ?: documentsResult.error
        )
    }
}
