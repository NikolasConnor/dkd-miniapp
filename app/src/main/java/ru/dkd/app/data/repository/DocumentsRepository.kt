package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

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
        val passportResult = apiCall(token, "GET", "/api/passport")
        val passportJson = passportResult.data
        val documentsResult = apiCall(token, "GET", "/api/documents")
        val documentsJson = documentsResult.data

        return DocumentsData(
            hasPassport = passportJson?.optBoolean("has_passport", false) ?: false,
            passport = passportJson?.optJSONObject("passport"),
            mandates = documentsJson?.array("mandates") ?: emptyList(),
            presidentCertificates = documentsJson?.array("president_certificate") ?: emptyList(),
            decrees = documentsJson?.array("decrees") ?: emptyList(),
            laws = documentsJson?.array("laws") ?: emptyList(),
            passportLoadFailed = passportResult.error != null || passportJson == null,
            documentsLoadFailed = documentsResult.error != null || documentsJson == null,
            error = passportResult.error ?: documentsResult.error
        )
    }
}
