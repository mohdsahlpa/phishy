package com.test.phishy

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object DomainBlocklist {

    private var blockedDomains = emptySet<String>()

    /**
     * Loads the blocklist from the res/raw/blocklist.txt file.
     * This should be called once when the application starts.
     */
    fun loadBlocklist(context: Context) {
        // Use a try-with-resources block to ensure the reader is closed automatically.
        try {
            context.resources.openRawResource(R.raw.blocklist).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Read all lines from the file and store them in the set.
                    blockedDomains = reader.readLines().toSet()
                }
            }
        } catch (e: Exception) {
            // Log an error if the file can't be read.
            e.printStackTrace()
        }
    }

    /**
     * Checks if a given domain is on the blocklist.
     * This check is efficient and also blocks subdomains.
     */
    fun isBlocked(domain: String): Boolean {
        return blockedDomains.any { blockedDomain ->
            domain == blockedDomain || domain.endsWith(".$blockedDomain")
        }
    }
}
