package com.justme.xtls_core_proxy.subs

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity

/**
 * Exported handler for `bkvpn://add?sub=...` and `https://boykiss3r.site/app/add?sub=...`.
 * Validates the payload against approved domains, then hands the validated URL to MainActivity
 * (whose viewModelScope outlives this transient activity, and which owns the single user
 * confirmation before the subscription is actually added). This activity performs no add
 * itself and shows no UI; it routes and finishes.
 */
class BoykisserLinkActivity : LocalizedComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sub = intent?.data?.getQueryParameter(BoykisserCallback.PARAM_SUB)
        val approved = BoykisserCallback.validate(sub)

        if (approved == null) {
            Toast.makeText(this, R.string.boykisser_error_invalid_domain, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_ADD_BOYKISSER_SUB, approved)
                }
            )
        }
        finish()
    }
}
