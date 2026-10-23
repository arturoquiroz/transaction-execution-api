package com.aquiroz.transactions.adapter.out.id;

import com.aquiroz.transactions.domain.port.TransactionIdGenerator;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidV7TransactionIdGenerator implements TransactionIdGenerator {

    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    @Override
    public UUID nextId() {
        return generator.generate();
    }
}
