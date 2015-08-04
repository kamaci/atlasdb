/**
 * // Copyright 2015 Palantir Technologies
 * //
 * // Licensed under the BSD-3 License (the "License");
 * // you may not use this file except in compliance with the License.
 * // You may obtain a copy of the License at
 * //
 * // http://opensource.org/licenses/BSD-3-Clause
 * //
 * // Unless required by applicable law or agreed to in writing, software
 * // distributed under the License is distributed on an "AS IS" BASIS,
 * // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * // See the License for the specific language governing permissions and
 * // limitations under the License.
 */
package com.palantir.paxos;

/**
 * Logs can be truncated to save disk space. This exception may be thrown if truncated state is
 * requested.
 *
 * @author rullman
 */
public class TruncatedStateLogException extends Exception {
    private static final long serialVersionUID = 1L;

    public TruncatedStateLogException(String message, Throwable cause) {
        super(message, cause);
    }

    public TruncatedStateLogException(String message) {
        super(message);
    }
}
