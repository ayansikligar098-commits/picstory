package com.picstory.app

import org.json.JSONObject

data class User(
    val id: Int,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String,
    val musicUrl: String,
    val postCount: Int,
    val followersCount: Int,
    val followingCount: Int,
    val followedByMe: Boolean,
    val online: Boolean,
    val lastSeenAt: String?,
    val createdAt: String?
) {
    companion object {
        fun fromJson(o: JSONObject): User = User(
            id = o.optInt("id"),
            username = o.optString("username"),
            displayName = o.optString("display_name"),
            bio = o.optString("bio"),
            avatarUrl = o.optString("avatar_url"),
            musicUrl = o.optString("music_url"),
            postCount = o.optInt("post_count"),
            followersCount = o.optInt("followers_count"),
            followingCount = o.optInt("following_count"),
            followedByMe = o.optBoolean("followed_by_me"),
            online = o.optBoolean("online"),
            lastSeenAt = o.optString("last_seen_at").takeIf { it.isNotEmpty() },
            createdAt = o.optString("created_at").takeIf { it.isNotEmpty() }
        )
    }
}

data class StoryItem(
    val id: Int,
    val userId: Int,
    val kind: String,
    val text: String,
    val imageUrl: String,
    val bgColor: String,
    val viewedByMe: Boolean,
    val likeCount: Int,
    val likedByMe: Boolean,
    val createdAt: String,
    val expiresAt: String
) {
    companion object {
        fun fromJson(o: JSONObject): StoryItem = StoryItem(
            id = o.optInt("id"),
            userId = o.optInt("user_id"),
            kind = o.optString("kind", "image"),
            text = o.optString("text"),
            imageUrl = o.optString("image_url"),
            bgColor = o.optString("bg_color", "#5B21B6"),
            viewedByMe = o.optBoolean("viewed_by_me"),
            likeCount = o.optInt("like_count"),
            likedByMe = o.optBoolean("liked_by_me"),
            createdAt = o.optString("created_at"),
            expiresAt = o.optString("expires_at")
        )
    }
}

data class StoryGroup(val user: User, val stories: List<StoryItem>) {
    companion object {
        fun fromJson(o: JSONObject): StoryGroup {
            val list = mutableListOf<StoryItem>()
            val arr = o.optJSONArray("stories")
            if (arr != null) for (i in 0 until arr.length()) list.add(StoryItem.fromJson(arr.getJSONObject(i)))
            return StoryGroup(User.fromJson(o.getJSONObject("user")), list)
        }
    }
}

data class Post(
    val id: Int,
    val userId: Int,
    val author: User?,
    val caption: String,
    val imageUrl: String,
    var likeCount: Int,
    var commentCount: Int,
    var likedByMe: Boolean,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject): Post = Post(
            id = o.optInt("id"),
            userId = o.optInt("user_id"),
            author = if (o.isNull("author")) null else User.fromJson(o.getJSONObject("author")),
            caption = o.optString("caption"),
            imageUrl = o.optString("image_url"),
            likeCount = o.optInt("like_count"),
            commentCount = o.optInt("comment_count"),
            likedByMe = o.optBoolean("liked_by_me"),
            createdAt = o.optString("created_at")
        )
    }
}

data class Comment(
    val id: Int,
    val postId: Int,
    val userId: Int,
    val author: User?,
    val text: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject): Comment = Comment(
            id = o.optInt("id"),
            postId = o.optInt("post_id"),
            userId = o.optInt("user_id"),
            author = if (o.isNull("author")) null else User.fromJson(o.getJSONObject("author")),
            text = o.optString("text"),
            createdAt = o.optString("created_at")
        )
    }
}

data class DmMessage(
    val id: Int,
    val conversationId: Int,
    val senderId: Int,
    val body: String,
    val createdAt: String?,
    val readAt: String?
) {
    companion object {
        fun fromJson(o: JSONObject): DmMessage = DmMessage(
            id = o.optInt("id"),
            conversationId = o.optInt("conversation_id"),
            senderId = o.optInt("sender_id"),
            body = o.optString("body"),
            createdAt = o.optString("created_at").takeIf { it.isNotEmpty() },
            readAt = o.optString("read_at").takeIf { it.isNotEmpty() }
        )
    }
}

data class DmConversation(
    val user: User,
    val lastMessage: String,
    val lastMessageAt: String?,
    val unreadCount: Int
) {
    companion object {
        fun fromJson(o: JSONObject): DmConversation = DmConversation(
            user = User.fromJson(o.getJSONObject("user")),
            lastMessage = o.optString("last_message"),
            lastMessageAt = o.optString("last_message_at").takeIf { it.isNotEmpty() },
            unreadCount = o.optInt("unread_count")
        )
    }
}

data class Highlight(
    val id: Int,
    val userId: Int,
    val name: String,
    val coverUrl: String,
    val storyCount: Int,
    val stories: List<StoryItem>,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject): Highlight {
            val stories = mutableListOf<StoryItem>()
            val arr = o.optJSONArray("stories")
            if (arr != null) for (i in 0 until arr.length()) stories.add(StoryItem.fromJson(arr.getJSONObject(i)))
            return Highlight(
                id = o.optInt("id"),
                userId = o.optInt("user_id"),
                name = o.optString("name"),
                coverUrl = o.optString("cover_url"),
                storyCount = o.optInt("story_count"),
                stories = stories,
                createdAt = o.optString("created_at")
            )
        }
    }
}
