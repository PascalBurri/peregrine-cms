var cmpAdminComponentsAction = (function () {
'use strict';

var template = {render: function(){var _vm=this;var _h=_vm.$createElement;var _c=_vm._self._c||_h;return _c('a',{class:_vm.classes,attrs:{"href":_vm.target},on:{"click":function($event){$event.stopPropagation();$event.preventDefault();_vm.action($event);}}},[_vm._v(_vm._s(_vm.title)),_vm._t("default")],2)},staticRenderFns: [],
    props: ['title', 'command', 'target', 'classes' ],
    methods: {
        action: function(e) {
            perHelperAction(this, this.command, this.target);
        }
    }
};

return template;

}());