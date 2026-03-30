package com.example.springapi.service;

import com.example.springapi.dto.CustomerDto;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.SequencedCollection;

@Service
public class RecentCustomerBuffer {

    private final LinkedList<CustomerDto> recent = new LinkedList<>();

    public synchronized void add(CustomerDto dto) {
        recent.addFirst(dto);
        if (recent.size() > 10) {
            recent.removeLast();
        }
    }

    public synchronized List<CustomerDto> getRecent() {
        SequencedCollection<CustomerDto> view = recent;
        return List.copyOf(view);
    }
}
