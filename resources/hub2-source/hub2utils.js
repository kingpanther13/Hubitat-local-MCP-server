const stripHtml = function(html) {
    return html ? html.replace(/<(?:.|\n)*?>/gm, '') : '';
}

jQuery.expr[':'].containsCaseInsensitive = function(a, i, m) {
    const searchIn = jQuery(a).text().toUpperCase().normalize('NFD').replace(/[\u0300-\u036f]/g, "");
    const searchSubstr = m[3].toUpperCase().normalize('NFD').replace(/[\u0300-\u036f]/g, "");
    return searchIn.indexOf(searchSubstr) >= 0;
};

function saveScrollPosition(localStorageName, divName) {
    if (!divName) divName = 'divMainUIContent';
    const element = document.getElementById(divName);
    if (element) {
        const scrollPosition = element.scrollTop;
        localStorage.setItem(localStorageName, scrollPosition);
        // console.log(`saved scroll position ${scrollPosition} for ${localStorageName} on ${divName}`);
    }
}

function restoreScrollPosition(localStorageName, divName) {
    if (!divName) divName = 'divMainUIContent';
    const scrollPosition = localStorage.getItem(localStorageName);
    if (scrollPosition) {
        const element = document.getElementById(divName);
        if (element) {
            element.scrollTop = scrollPosition;
            localStorage.setItem(localStorageName, 0);
            // console.log(`restored scroll position ${scrollPosition} for ${localStorageName} on ${divName}`);
        }
    }
}

function selectSettingsInLeftMenu() {
    globalMenuSelection = 'Settings';
}

function addSettingsLinkToTop() {
    // no longer needed in the updated UI
    // $("#divAppUIContainer").parent().prepend('<div class="nav" role="navigation"><ul class="nav nav-pills"><li><a href="/hub/edit">&laquo; Settings</a></li></ul></div>');
}

function scrollToPageBottom() {
    const divMainUIContent = document.getElementById('divMainUIContent');
    divMainUIContent.scrollTop = divMainUIContent.scrollHeight;
    return false;
}

function showHeaderSpinner() {
    $('#spinnerInHeader').show();
}

function hideHeaderSpinner() {
    $('#spinnerInHeader').hide();
}

function calculateVerticalPaddingAndMargin(selector) {
    const div = $(selector);
    return div && div.length > 0 ? div.outerHeight() - div.height() + (div.outerWidth(true) - div.outerWidth()) : 0;
}

function focusOnFirstEmptyTextInput() {
    const appInputs = $("[id^='settings['],[id^='hours[']");
    for (let i = 0; i < appInputs.length; i++) {
        const input = document.getElementById(appInputs[i].id);
        if (isElementInViewport(input)) {
            const tagName = input.tagName;
            const inputValue = $(input).val();
            if ((tagName == 'INPUT' || tagName == 'TEXTAREA') && !inputValue && input.offsetHeight > 0) {
                // console.log(`setting focus on input with id='${input.id}' and name='${input.name}'`);
                input.focus();
                try {
                    input.setSelectionRange(0, 0);
                } catch (e) {
                    // ignore
                }
                input.click();
                break;
            }
        }
    }
}

function isElementInViewport(element) {
    const rect = element.getBoundingClientRect();
    return (
        rect.top >= 0 &&
        rect.left >= 0 &&
        rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&
        rect.right <= (window.innerWidth || document.documentElement.clientWidth)
    );
}

function searchInElements(searchValue, selector) {
    if (!searchValue) {
        $(selector).show()
    } else {
        $(selector).hide(),
        $(selector + ':containsCaseInsensitive("' + searchValue + '")').show()
    }
}

function stopClickPropagation(event) {
    event.stopPropagation();
}