package com.statsig.androidsdk

/** A helper class for interfacing with Feature Gate defined in the Statsig console */
class FeatureGate(
    private val name: String,
    private val details: EvalDetails,
    private val value: Boolean,
    private val rule: String = "",
    private val groupName: String? = null,
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val idType: String? = null
) : BaseConfig(name, details) {
    internal companion object {
        fun getError(name: String): FeatureGate = FeatureGate(
            name,
            EvalDetails(EvalSource.Error, EvalReason.Unrecognized, lcut = 0),
            false,
            ""
        )
    }

    /** getSecondaryExposures() as JSON, when this gate came from the SDK's SQL. */
    @Transient
    internal var secondaryExposuresJson: String? = null

    /** Parameters of this gate's automatic exposure, built on first use. */
    @Transient
    internal var exposureParams: Map<String, Any?>? = null

    fun getValue(): Boolean = this.value

    fun getRuleID(): String = this.rule

    fun getGroupName(): String? = this.groupName

    fun getSecondaryExposures(): Array<Map<String, String>> = this.secondaryExposures

    fun getIDType(): String? = this.idType
}
