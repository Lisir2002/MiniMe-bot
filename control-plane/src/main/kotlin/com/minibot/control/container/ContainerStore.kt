package com.minibot.control.container

interface ContainerStore {
    fun createProfile(profile: ContainerProfile): ContainerProfile

    fun findProfile(profileId: String): ContainerProfile?

    fun listProfiles(): List<ContainerProfile>

    fun findInstance(profileId: String): ContainerInstance?

    fun saveInstance(instance: ContainerInstance)

    fun updateInstance(
        profileId: String,
        phase: ContainerPhase,
        containerId: String? = null,
        lastError: String? = null,
    )

    fun setPinned(profileId: String, pinned: Boolean)

    fun deleteProfile(profileId: String)
}
