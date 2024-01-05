// a better version of select that is populated with Ajax request
// unlike standard Jenkins select implementation, it
// * also triggers dependent selects population when selected value for this one is set due to being the only available option;
// * caches server responses for a given set of parameters.

if (originalUpdateListBox == null) {
    // protect against double script execution

    var originalUpdateListBox = updateListBox;

    updateListBox = function () {
        var cache = {};

        function updateItems(listBox, result) {
            var currentSelection = listBox.value;

            // clear the contents
            while (listBox.length > 0) {
                listBox.options[0] = null;
            }

            var selectionSet = false; // is the selection forced by the server?
            var possibleIndex = null; // if there's a new option that matches the current value, remember its index

            var opts = result.values;
            for (var i = 0; i < opts.length; i++) {
                listBox.options[i] = new Option(opts[i].name, opts[i].value);
                if (opts[i].selected) {
                    listBox.selectedIndex = i;
                    selectionSet = true;
                }
                if (opts[i].value === currentSelection) {
                    possibleIndex = i;
                }
            }

            // if no value is explicitly selected by the server, try to select the same value
            if (!selectionSet && possibleIndex != null) {
                listBox.selectedIndex = possibleIndex;
            } else {
                listBox.selectedIndex = 0;
            }
        }

        return function (listBox, url, config) {
            if (listBox.classList.contains("jb-space-cache")) {
                var cacheKey = Object.entries(config.parameters).map(([key, value]) => encodeURIComponent(key) + '=' + encodeURIComponent(value)).join('&');
                var cachedResponse = (cache[url] || {})[cacheKey];
                if (cachedResponse) {
                    updateItems(listBox, cachedResponse);
                    config.onSuccess(cachedResponse);
                } else {
                    var originalOnSuccess = config.onSuccess;
                    config.onSuccess = function (rsp) {
                        (cache[url] || (cache[url] = {}))[cacheKey] = rsp.responseJSON;
                        originalOnSuccess(rsp);
                    };
                    originalUpdateListBox(listBox, url, config);
                }
            } else {
                originalUpdateListBox(listBox, url, config);
            }
        }
    }();

    Behaviour.specify("SELECT.jb-space-autofill", "jb-space-autofill", 10, function (el) {
        el.addEventListener("filled", function () {
            if (el.options.length > 0 && el.options[0].value == el.value) {
                fireEvent(el, "change");
            }
        });
    });
}