package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "chat_room")
public class Chatroom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long chatroomId;

    /**
     * 하나의 Room은 하나의 ChatRoom만 가질 수 있음
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "room_id",
            nullable = false,
            unique = true
    )
    private Room room;

    /**
     * 하나의 ChatRoom에는 여러 개의 Chat이 존재
     */
    @OneToMany(
            mappedBy = "chatRoom",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<Chat> chats = new ArrayList<>();
}