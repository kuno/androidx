/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.ui.core.samples

import androidx.annotation.Sampled
import androidx.compose.Composable
import androidx.ui.core.Modifier
import androidx.ui.core.WithConstraints
import androidx.compose.foundation.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.preferredSize
import androidx.compose.ui.unit.dp

@Sampled
@Composable
fun WithConstraintsSample() {
    WithConstraints {
        val rectangleHeight = 100.dp
        if (maxHeight < rectangleHeight * 2) {
            Box(Modifier.preferredSize(50.dp, rectangleHeight), backgroundColor = Color.Blue)
        } else {
            Column {
                Box(Modifier.preferredSize(50.dp, rectangleHeight), backgroundColor = Color.Blue)
                Box(Modifier.preferredSize(50.dp, rectangleHeight), backgroundColor = Color.Gray)
            }
        }
    }
}
