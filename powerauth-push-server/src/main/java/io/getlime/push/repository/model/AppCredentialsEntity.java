/*
 * Copyright 2016 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.push.repository.model;

import javax.persistence.*;
import java.io.Serializable;

/**
 * Class representing application tokens used to authenticate against APNs or FCM services.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Entity
@Table(name = "push_app_credentials")
public class AppCredentialsEntity implements Serializable {
    private static final long serialVersionUID = -8904070389354612019L;

    @Id
    @Column(name = "id")
    @SequenceGenerator(name = "push_app_credentials", sequenceName = "push_credentials_seq")
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "push_app_credentials")
    private Long id;

    @Column(name = "app_id", nullable = false, updatable = false)
    private Long appId;

    @Column(name = "ios_private_key")
    private byte[] iosPrivateKey;

    @Column(name = "ios_team_id")
    private String iosTeamId;

    @Column(name = "ios_key_id")
    private String iosKeyId;

    @Column(name = "ios_bundle")
    private String iosBundle;

    @Column(name = "android_private_key")
    private byte[] androidPrivateKey;

    @Column(name = "android_project_id")
    private String androidProjectId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public String getIosBundle() {
        return iosBundle;
    }

    public void setIosBundle(String iosBundle) {
        this.iosBundle = iosBundle;
    }

    public byte[] getIosPrivateKey() {
        return iosPrivateKey;
    }

    public void setIosPrivateKey(byte[] iosPrivateKey) {
        this.iosPrivateKey = iosPrivateKey;
    }

    public String getIosTeamId() {
        return iosTeamId;
    }

    public void setIosTeamId(String iosTeamId) {
        this.iosTeamId = iosTeamId;
    }

    public String getIosKeyId() {
        return iosKeyId;
    }

    public void setIosKeyId(String iosKeyId) {
        this.iosKeyId = iosKeyId;
    }

    public byte[] getAndroidPrivateKey() {
        return androidPrivateKey;
    }

    public void setAndroidPrivateKey(byte[] androidPrivateKey) {
        this.androidPrivateKey = androidPrivateKey;
    }

    public String getAndroidProjectId() {
        return androidProjectId;
    }

    public void setAndroidProjectId(String androidProjectId) {
        this.androidProjectId = androidProjectId;
    }
}
