/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.serialization.schema

/**
 * Root of the serialization type hierarchy.
 *
 * See [ComplexType], [Scalar], and [WellKnownType] for the main branches of the type tree.
 */
interface Type {
    /** The kind of type represented by this type. */
    val typeKind: Kind

    enum class Kind {
        /** Types declared by the client, including [Message], [Enum], and [Service]. */
        DECLARED,

        /** Scalar types. */
        SCALAR,

        /** Well-known complex types from the `google.protobuf` package. */
        WELL_KNOWN
    }
}
