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

const PROVIDER_NAME = "otel";
const SAMPLER_TYPE_ALWAYS_ON = "always_on";
const SAMPLER_TYPE_ALWAYS_OFF = "always_off";
const SAMPLER_TYPE_TRACE_ID_RATIO = "traceidratio";
const SAMPLER_TYPE_PARENT_BASED_ALWAYS_ON = "parentbased_always_on";
const SAMPLER_TYPE_PARENT_BASED_ALWAYS_OFF = "parentbased_always_off";
const SAMPLER_TYPE_PARENT_BASED_TRACE_ID_RATIO = "parentbased_traceidratio";
const SAMPLER_TYPE_RATE_LIMITING = "ratelimiting";
const DEFAULT_SAMPLER_TYPE = SAMPLER_TYPE_ALWAYS_ON;
