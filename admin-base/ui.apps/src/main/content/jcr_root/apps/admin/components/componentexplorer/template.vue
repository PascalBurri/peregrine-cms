<template>
    <div style="height: 90%; ">
        <p>components</p>
        <div class="collection" style="height: 100%; overflow: auto;">
           <a draggable="true" v-on:dragstart="onDragStart(cmp, $event)" class="collection-item" v-for="cmp in this.$root.$data.admin.components.data">{{cmp.path.split('/')[2]}} {{cmp.name}}</a>
        </div>
    </div>
</template>

<script>
    export default {
        props: ['model'],
        methods: {
            onDragStart: function(cmp, ev) {
                if(ev) {
                    ev.dataTransfer.setData('component', cmp.path)
                }
            }
        }
    }
</script>