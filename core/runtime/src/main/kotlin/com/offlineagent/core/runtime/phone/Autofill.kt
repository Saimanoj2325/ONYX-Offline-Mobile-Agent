package com.offlineagent.core.runtime.phone

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Profile fields the vault knows. Cards and passwords are deliberately absent (only as user-added custom fields). */
enum class ProfileKey(val label: String, val section: String) {
    FULL_NAME("Full name", "Personal"), GIVEN_NAME("First name", "Personal"), FAMILY_NAME("Last name", "Personal"),
    EMAIL("Email", "Personal"), PHONE("Phone", "Personal"), DATE_OF_BIRTH("Date of birth", "Personal"), GENDER("Gender", "Personal"),
    ADDRESS_LINE1("Address line 1", "Address"), ADDRESS_LINE2("Address line 2", "Address"), CITY("City", "Address"),
    STATE("State", "Address"), POSTAL_CODE("PIN / postal code", "Address"), COUNTRY("Country", "Address"),
    COLLEGE("College / university", "Education"), ROLL_NUMBER("Roll / registration number", "Education"), DEGREE("Degree", "Education"),
    BRANCH("Branch / major", "Education"), GRADUATION_YEAR("Graduation year", "Education"), CGPA("CGPA / percentage", "Education"),
    CURRENT_YEAR("Current year of study", "Education"),
    LINKEDIN("LinkedIn URL", "Profiles"), GITHUB("GitHub URL", "Profiles"), PORTFOLIO("Portfolio / website", "Profiles"),
    SKILLS("Skills", "Resume"), SUMMARY("Short bio / summary", "Resume"), EXPERIENCE("Experience", "Resume"), PROJECTS("Projects", "Resume"),
    COMPANY("Current company", "Resume"), JOB_TITLE("Current role", "Resume"),
}

@Serializable
data class CustomField(val label: String, val value: String, val sensitive: Boolean = false)

/** Decrypted vault contents (kept in memory only while unlocked). */
@Serializable
data class Profile(val values: Map<String, String> = emptyMap(), val custom: List<CustomField> = emptyList()) {
    operator fun get(k: ProfileKey): String? = values[k.name]?.takeIf { it.isNotBlank() } ?: derived(k)

    private fun derived(k: ProfileKey): String? = when (k) {
        ProfileKey.FULL_NAME -> listOfNotNull(values[ProfileKey.GIVEN_NAME.name], values[ProfileKey.FAMILY_NAME.name]).joinToString(" ").ifBlank { null }
        ProfileKey.GIVEN_NAME -> values[ProfileKey.FULL_NAME.name]?.trim()?.substringBefore(' ')?.ifBlank { null }
        ProfileKey.FAMILY_NAME -> values[ProfileKey.FULL_NAME.name]?.trim()?.takeIf { ' ' in it }?.substringAfterLast(' ')
        else -> null
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): Profile = json.decodeFromString(serializer(), s)
    }
}

/** A form field as seen by the autofill service or the accessibility tree. */
data class FormField(
    val index: Int,
    val autofillHints: List<String> = emptyList(),
    val hint: String = "",
    val label: String = "",
    val viewId: String = "",
    val inputType: Int = 0,
    val htmlName: String = "",
    val htmlType: String = "",
    val currentValue: String = "",
    val isPassword: Boolean = false,
) {
    val words: String get() = normalize(listOf(autofillHints.joinToString(" "), hint, label, viewId.substringAfterLast('/'), htmlName, htmlType).joinToString(" "))

    companion object {
        fun normalize(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[_\\-.:/]"), " ").lowercase()
    }
}

data class FieldMatch(val field: FormField, val key: ProfileKey?, val custom: CustomField?, val confidence: Double, val source: String) {
    fun value(p: Profile): String? = custom?.value ?: key?.let { p[it] }
}

/**
 * Maps form fields to profile values: Android autofill hints and HTML autocomplete tokens first (exact), then keyword
 * rules over hint/label/id text, then — for what is left — one grammar-constrained LLM call that may only answer with
 * a profile key or "none". Password, OTP and card fields are never filled.
 */
class AutofillMapper(private val llm: AgentLlm? = null) {
    private val hintMap = mapOf(
        "name" to ProfileKey.FULL_NAME, "personname" to ProfileKey.FULL_NAME, "personfullname" to ProfileKey.FULL_NAME,
        "persongivenname" to ProfileKey.GIVEN_NAME, "personfamilyname" to ProfileKey.FAMILY_NAME, "given-name" to ProfileKey.GIVEN_NAME,
        "family-name" to ProfileKey.FAMILY_NAME, "emailaddress" to ProfileKey.EMAIL, "email" to ProfileKey.EMAIL,
        "phone" to ProfileKey.PHONE, "phonenumber" to ProfileKey.PHONE, "tel" to ProfileKey.PHONE, "phonenational" to ProfileKey.PHONE,
        "postaladdress" to ProfileKey.ADDRESS_LINE1, "streetaddress" to ProfileKey.ADDRESS_LINE1, "address-line1" to ProfileKey.ADDRESS_LINE1,
        "address-line2" to ProfileKey.ADDRESS_LINE2, "extendedaddress" to ProfileKey.ADDRESS_LINE2, "addresslocality" to ProfileKey.CITY,
        "address-level2" to ProfileKey.CITY, "addressregion" to ProfileKey.STATE, "address-level1" to ProfileKey.STATE,
        "postalcode" to ProfileKey.POSTAL_CODE, "postal-code" to ProfileKey.POSTAL_CODE, "addresscountry" to ProfileKey.COUNTRY,
        "country" to ProfileKey.COUNTRY, "country-name" to ProfileKey.COUNTRY, "birthdatefull" to ProfileKey.DATE_OF_BIRTH, "bday" to ProfileKey.DATE_OF_BIRTH,
        "gender" to ProfileKey.GENDER, "sex" to ProfileKey.GENDER, "organization" to ProfileKey.COMPANY, "organization-title" to ProfileKey.JOB_TITLE, "url" to ProfileKey.PORTFOLIO,
    )

    /** Ordered: first matching rule wins; more specific rules first. */
    private val rules: List<Pair<Regex, ProfileKey?>> = listOf(
        // Someone else's details: never fill from the user's own profile.
        "\\b(father|mother|parent|guardian|spouse|husband|wife|reference|referee|emergency|nominee)" to null,
        "\\b(roll|registration|reg|enrol+ment|admission|htno|hall ?ticket|student ?id|usn|prn)\\b" to ProfileKey.ROLL_NUMBER,
        "\\b(college|university|institute|institution|school)\\b" to ProfileKey.COLLEGE,
        "\\b(cgpa|gpa|percentage|aggregate|marks)\\b" to ProfileKey.CGPA,
        "\\b(graduation|passing|pass ?out|batch)\\b.*\\byear\\b|\\byear of (graduation|passing)\\b" to ProfileKey.GRADUATION_YEAR,
        "\\b(current|which) year\\b|\\byear of study\\b|\\bsemester\\b" to ProfileKey.CURRENT_YEAR,
        "\\b(branch|major|specialization|specialisation|department|stream|discipline)\\b" to ProfileKey.BRANCH,
        "\\b(degree|qualification|course|program|programme)\\b" to ProfileKey.DEGREE,
        "linkedin" to ProfileKey.LINKEDIN, "github" to ProfileKey.GITHUB, "\\b(portfolio|website|personal site|homepage)\\b" to ProfileKey.PORTFOLIO,
        "\\b(skills?|technologies|tech stack)\\b" to ProfileKey.SKILLS,
        "\\b(about you|bio|summary|cover letter|why should we|tell us about)\\b" to ProfileKey.SUMMARY,
        "\\b(experience|work history|internships?)\\b" to ProfileKey.EXPERIENCE, "\\bprojects?\\b" to ProfileKey.PROJECTS,
        "\\b(company|employer|organi[sz]ation)\\b" to ProfileKey.COMPANY, "\\b(job title|designation|role|position)\\b" to ProfileKey.JOB_TITLE,
        "\\b(e-?mail|mail id)\\b" to ProfileKey.EMAIL,
        "\\b(phone|mobile|contact (no|number)|whatsapp|cell)\\b" to ProfileKey.PHONE,
        "\\b(first|given|fore) ?name\\b|\\bfname\\b" to ProfileKey.GIVEN_NAME,
        "\\b(last|family|sur) ?name\\b|\\blname\\b|\\bsurname\\b" to ProfileKey.FAMILY_NAME,
        "\\b(full ?name|your name|candidate name|applicant name|student name|name)\\b" to ProfileKey.FULL_NAME,
        "\\b(dob|date of birth|birth ?date|birthday)\\b" to ProfileKey.DATE_OF_BIRTH, "\\b(gender|sex)\\b" to ProfileKey.GENDER,
        "\\b(address line ?2|apartment|apt|suite|landmark|locality|area)\\b" to ProfileKey.ADDRESS_LINE2,
        "\\b(address|street|house|flat|door)\\b" to ProfileKey.ADDRESS_LINE1,
        "\\b(city|town|district)\\b" to ProfileKey.CITY, "\\b(state|province|region)\\b" to ProfileKey.STATE,
        "\\b(pin ?code|pincode|zip|postal|post code)\\b" to ProfileKey.POSTAL_CODE, "\\bcountry\\b" to ProfileKey.COUNTRY,
    ).map { Regex(it.first) to it.second }

    private val neverFill = Regex("\\b(password|passwd|pin\\b(?! ?code)|otp|one.?time|cvv|cvc|card ?number|credit ?card|debit ?card|expiry|security code|captcha|aadhaar|pan ?(no|number|card))\\b")

    fun isNeverFill(f: FormField): Boolean = f.isPassword || neverFill.containsMatchIn(f.words) ||
        f.autofillHints.any { it.lowercase().contains("password") || it.lowercase().startsWith("creditcard") || it.lowercase().contains("otp") || it.lowercase().contains("smsotp") }

    fun ruleMatch(f: FormField, profile: Profile): FieldMatch {
        if (isNeverFill(f)) {
            // Only an explicitly added sensitive custom field whose label matches may be offered for these.
            val c = profile.custom.firstOrNull { it.sensitive && f.words.contains(FormField.normalize(it.label)) }
            return FieldMatch(f, null, c, if (c != null) 0.9 else 0.0, if (c != null) "custom-sensitive" else "never-fill")
        }
        for (h in f.autofillHints) hintMap[h.lowercase().replace("_", "")]?.let { return FieldMatch(f, it, null, 1.0, "autofill-hint") }
        hintMap[f.htmlName.lowercase()]?.let { return FieldMatch(f, it, null, 0.95, "html-autocomplete") }
        profile.custom.filter { !it.sensitive }.firstOrNull { it.label.isNotBlank() && f.words.contains(FormField.normalize(it.label)) }
            ?.let { return FieldMatch(f, null, it, 0.85, "custom-label") }
        val w = f.words
        for ((re, k) in rules) if (re.containsMatchIn(w)) return if (k == null) FieldMatch(f, null, null, 0.0, "other-person") else FieldMatch(f, k, null, 0.8, "keyword")
        // Android input types: TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x21, TYPE_CLASS_PHONE = 0x3, postal = 0x71
        when {
            f.inputType and 0xFFF == 0x21 -> return FieldMatch(f, ProfileKey.EMAIL, null, 0.75, "input-type")
            f.inputType and 0xF == 0x3 -> return FieldMatch(f, ProfileKey.PHONE, null, 0.7, "input-type")
            f.inputType and 0xFFF == 0x71 -> return FieldMatch(f, ProfileKey.POSTAL_CODE, null, 0.7, "input-type")
        }
        return FieldMatch(f, null, null, 0.0, "unknown")
    }

    /** Maps every field; unknown ones go to the LLM in a single call (if available). */
    suspend fun map(fields: List<FormField>, profile: Profile, context: String = ""): List<FieldMatch> {
        val first = fields.map { ruleMatch(it, profile) }
        val unknown = first.filter { it.source == "unknown" && (it.field.label.isNotBlank() || it.field.hint.isNotBlank() || it.field.viewId.isNotBlank()) }
        if (llm == null || unknown.isEmpty()) return first
        val available = ProfileKey.entries.filter { profile[it] != null }.map { it.name } + profile.custom.filter { !it.sensitive }.map { "CUSTOM:" + it.label }
        if (available.isEmpty()) return first
        val keyEnum = Schema.Str(enum = available + "none")
        val schema = Schema.Obj(listOf("fields" to Schema.Arr(Schema.Obj(listOf("index" to Schema.Num(null, 0.0, fields.size.toDouble(), true), "key" to keyEnum)), maxItems = unknown.size.coerceAtLeast(1))))
        val sys = "Map web/app form fields to the user's saved profile keys. Answer \"none\" when unsure. Never map password, OTP, card or ID-number fields."
        val user = buildString {
            if (context.isNotBlank()) append("Form context: ").append(context.take(300)).append('\n')
            append("Profile keys available: ").append(available.joinToString()).append('\n')
            append("Fields:\n")
            unknown.forEach { m -> append("index ").append(m.field.index).append(": ").append(m.field.words.take(160)).append('\n') }
        }
        val raw = runCatching { llm!!.complete(sys, user, Gbnf.of(schema), 60 + unknown.size * 24) }.getOrNull() ?: return first
        val picks = runCatching {
            ((Json.parseToJsonElement(raw.substring(raw.indexOf('{'))) as JsonObject)["fields"] as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val idx = (o["index"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                idx to ((o["key"] as? JsonPrimitive)?.contentOrNull ?: "none")
            }.toMap()
        }.getOrDefault(emptyMap())
        return first.map { m ->
            if (m.source != "unknown") return@map m
            val k = picks[m.field.index] ?: return@map m
            when {
                k == "none" -> m
                k.startsWith("CUSTOM:") -> profile.custom.firstOrNull { it.label == k.removePrefix("CUSTOM:") }?.let { FieldMatch(m.field, null, it, 0.6, "llm") } ?: m
                else -> runCatching { ProfileKey.valueOf(k) }.getOrNull()?.let { FieldMatch(m.field, it, null, 0.6, "llm") } ?: m
            }
        }
    }
}
