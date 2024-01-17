package org.jetbrains.space.jenkins.steps

import net.sf.json.JSONObject
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable


fun flattenNestedObject(args: HashMap<String, Any?>, jsonKey: String) {
    (args[jsonKey] as? UninstantiatedDescribable)?.let { child ->
        args.putAll(child.arguments)
        args.remove(jsonKey)
    }
}

fun flattenNestedObject(formData: JSONObject, jsonKey: String) {
    if (formData.has(jsonKey)) {
        val customSpaceConnection: JSONObject = formData.getJSONObject(jsonKey)
        formData.putAll(customSpaceConnection)
        formData.remove(jsonKey)
    }
}