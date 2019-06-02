/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.crypto.actions

import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.internal.crypto.keysbackup.KeysBackup
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import timber.log.Timber

internal class SetDeviceVerificationAction(private val mCryptoStore: IMXCryptoStore,
                                           private val mCredentials: Credentials,
                                           private val mKeysBackup: KeysBackup) {

    fun handle(verificationStatus: Int, deviceId: String, userId: String) {
        val device = mCryptoStore.getUserDevice(deviceId, userId)

        // Sanity check
        if (null == device) {
            Timber.w("## setDeviceVerification() : Unknown device $userId:$deviceId")
            return
        }

        if (device.mVerified != verificationStatus) {
            device.mVerified = verificationStatus
            mCryptoStore.storeUserDevice(userId, device)

            if (userId == mCredentials.userId) {
                // If one of the user's own devices is being marked as verified / unverified,
                // check the key backup status, since whether or not we use this depends on
                // whether it has a signature from a verified device
                mKeysBackup.checkAndStartKeysBackup()
            }
        }
    }
}