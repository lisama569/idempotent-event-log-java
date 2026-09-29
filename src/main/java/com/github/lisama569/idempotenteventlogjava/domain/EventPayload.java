package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 事件载荷（canonical 内部表示）。
 * 旧版本载荷经 codec 迁移后也统一表示为这些类型。
 */
public sealed interface EventPayload
        permits EventPayload.CreateDocument, EventPayload.SetField, EventPayload.DeleteDocument {

    String typeName();

    /** CREATE_DOCUMENT：v1 起存在，载荷为文档标题。 */
    record CreateDocument(String title) implements EventPayload {
        @Override
        public String typeName() {
            return "CREATE_DOCUMENT";
        }
    }

    /** SET_FIELD：v1 只有 field/value；v2 增加 transform（v1 迁移为 NONE）。 */
    record SetField(String field, String value, Transform transform) implements EventPayload {
        @Override
        public String typeName() {
            return "SET_FIELD";
        }
    }

    /** DELETE_DOCUMENT：空载荷。 */
    record DeleteDocument() implements EventPayload {
        @Override
        public String typeName() {
            return "DELETE_DOCUMENT";
        }
    }
}
