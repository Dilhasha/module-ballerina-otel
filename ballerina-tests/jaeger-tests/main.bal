// Copyright (c) 2026 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/observe;
import ballerina/otel as _;

// :9092 (instead of the :9091 used by the collector tests) so that both test
// packages can be run side by side without a port clash.
service /test on new http:Listener(9092) {

    resource function get sum(http:Caller caller) returns error? {
        ObservableAdderClass adder = new ObservableAdder(20, 33);
        int sum = adder.getSum();
        check caller->respond("Sum: " + sum.toString());
    }

    // Fails intentionally (HTTP 500) so the tests can assert that error
    // spans reach Jaeger with an error indication.
    resource function get failure() returns error {
        return error("intentional failure for error span export test");
    }
}

type ObservableAdderClass object {
    @observe:Observable
    function getSum() returns int;
};

class ObservableAdder {
    private final int firstNumber;
    private final int secondNumber;

    function init(int a, int b) {
        self.firstNumber = a;
        self.secondNumber = b;
    }

    function getSum() returns int {
        return self.firstNumber + self.secondNumber;
    }
}
