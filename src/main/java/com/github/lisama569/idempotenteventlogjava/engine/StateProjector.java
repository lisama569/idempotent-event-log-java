package com.github.lisama569.idempotenteventlogjava.engine;

import com.github.lisama569.idempotenteventlogjava.domain.AccountState;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.domain.NormalizedEvent;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import com.github.lisama569.idempotenteventlogjava.schema.PayloadCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 无状态状态投影器：把事件日志按序重放为账户状态。
 *
 * <p>规则（确定性）：</p>
 * <ol>
 *   <li>ACCOUNT_OPENED：账户必须不存在；重复开户冲突拒绝。</li>
 *   <li>DEPOSIT/WITHDRAWAL：账户必须已开立；取款后余额不允许为负。</li>
 * </ol>
 * 投影纯函数化：同样的事件序列永远得到同样的状态（金额均为归一化后的分）。
 */
@Component
public class StateProjector {

    private final PayloadCodec payloadCodec;

    public StateProjector(PayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec;
    }

    /** 重放事件列表并返回指定账户的状态；该账户从未出现则为空。 */
    public Optional<AccountState> project(List<StoredEvent> events, String accountId) {
        AccountState state = null;
        for (StoredEvent stored : events) {
            NormalizedEvent event = payloadCodec.normalize(stored);
            if (!accountId.equals(event.accountId())) {
                continue;
            }
            state = apply(state, event, stored.seq());
        }
        return Optional.ofNullable(state);
    }

    /** 应用单条事件。 */
    AccountState apply(AccountState current, NormalizedEvent event, long seq) {
        return switch (event.eventType()) {
            case "ACCOUNT_OPENED" -> {
                if (current != null) {
                    throw new EventLogException(ErrorReason.BUSINESS_CONFLICT,
                            "账户已开户，重复 ACCOUNT_OPENED: " + event.accountId()
                                    + "（seq=" + seq + "）");
                }
                yield new AccountState(event.accountId(), 0L, 1L, seq);
            }
            case "DEPOSIT" -> {
                if (current == null) {
                    throw new EventLogException(ErrorReason.BUSINESS_CONFLICT,
                            "账户未开户即发生存款: " + event.accountId() + "（seq=" + seq + "）");
                }
                yield new AccountState(current.accountId(),
                        current.balanceCents() + event.amountCents(),
                        current.version() + 1, seq);
            }
            case "WITHDRAWAL" -> {
                if (current == null) {
                    throw new EventLogException(ErrorReason.BUSINESS_CONFLICT,
                            "账户未开户即发生取款: " + event.accountId() + "（seq=" + seq + "）");
                }
                long newBalance = current.balanceCents() - event.amountCents();
                if (newBalance < 0) {
                    throw new EventLogException(ErrorReason.BUSINESS_CONFLICT,
                            "账户 " + event.accountId() + " 余额不足: 当前 "
                                    + current.balanceCents() + " 分, 试图取 "
                                    + event.amountCents() + " 分（seq=" + seq + "）");
                }
                yield new AccountState(current.accountId(), newBalance,
                        current.version() + 1, seq);
            }
            default -> throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                    "未知事件类型，无法投影: " + event.eventType());
        };
    }
}
