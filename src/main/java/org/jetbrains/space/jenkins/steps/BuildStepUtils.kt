package org.jetbrains.space.jenkins.steps

import net.sf.json.JSONObject
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable

/**
 * Flattens a nested JSONObject within the given formData object.
 * It removes the specified jsonKey and merges the nested JSONObject into the parent formData object.
 *
 * @param formData The parent formData object that contains the nested JSONObject.
 * @param jsonKey The key of the nested JSONObject within the formData.
 */
fun flattenNestedObject(formData: JSONObject, jsonKey: String) {
    if (formData.has(jsonKey)) {
        formData.putAll(formData.getJSONObject(jsonKey))
        formData.remove(jsonKey)
    }
}