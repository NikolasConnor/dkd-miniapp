package ru.dkd.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.network.loadDocumentsDto
import ru.dkd.app.core.network.loadPassportDto

internal data class DocumentsData(
    val hasPassport: Boolean,
    val passport: JSONObject?,
    val mandates: List<JSONObject>,
    val presidentCertificates: List<JSONObject>,
    val decrees: List<JSONObject>,
    val laws: List<JSONObject>,
    val passportLoadFailed: Boolean,
    val documentsLoadFailed: Boolean,
    val error: String?
)

internal class DocumentsRepository {
    suspend fun load(token: String): DocumentsData {
        val passportResult = loadPassportDto(token)
        val documentsResult = loadDocumentsDto(token)

        val passport = passportResult.data?.passport?.let { item ->
            JSONObject()
                .put("number", item.number)
                .put("full_name", item.fullName)
                .put("issued_at", item.issuedAt)
                .put("issued_by", item.issuedBy)
                .put("division_code", item.divisionCode)
                .put("status", item.status)
                .put("status_label", item.statusLabel)
                .put("country", item.country)
                .put("citizenship", item.citizenship)
                .put("citizen_id", item.citizenId)
        }

        fun documents(items: List<ru.dkd.app.core.network.DkdDocumentDto>) =
            items.map { item ->
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("description", item.description)
                    .put("status", item.status)
                    .put("created_at", item.createdAt)
            }

        val envelope = documentsResult.data
        return DocumentsData(
            hasPassport = passportResult.data?.hasPassport == true,
            passport = passport,
            mandates = documents(envelope?.mandates.orEmpty()),
            presidentCertificates = documents(envelope?.presidentCertificates.orEmpty()),
            decrees = documents(envelope?.decrees.orEmpty()),
            laws = documents(envelope?.laws.orEmpty()),
            passportLoadFailed = passportResult.error != null || passportResult.data == null,
            documentsLoadFailed = documentsResult.error != null || envelope == null,
            error = passportResult.error ?: documentsResult.error
        )
    }
}
