import {
  buildDoc,
  type DocWeaveDocument,
} from "@hmcts-cft/docweave";

import { buildAttendanceRegister } from "./attendance.js";
import type { DemoOrderInputs } from "./inputs.js";

export function buildDemoOrder(inputs: DemoOrderInputs): DocWeaveDocument {
  return buildDoc((order) => {
    const attendance = buildAttendanceRegister(inputs.attendances);
    if (attendance) {
      order.paragraph(
        "attendance-heard",
        `The Court heard from ${attendance}.`,
      );
    }

    if (inputs.includeOrderHeading) {
      order.paragraph("order-heading", "IT IS ORDERED THAT:");
    }

    order.orderedList("order-clauses", (list) => {
      list.item("give-possession", (content) => {
        content
          .text(
            inputs.useAlternativePossessionWording
              ? "The defendants shall give the claimant possession by "
              : "The defendants must give the claimant possession on or before ",
          )
          .fact("deadline", inputs.orderDate, { sourceId: "order-date" })
          .text(".");
      });

      if (inputs.includeRepairsClause) {
        list.item(
          "repairs",
          "The claimant must inspect the reported disrepair within 14 days.",
        );
      }

      if (inputs.includeCostsClause) {
        list.item("costs", (content) => {
          content
            .text("The defendants must pay the claimant's fixed costs of ")
            .fact("amount", inputs.fixedCostsAmount, {
              sourceId: "fixed-costs-amount",
            })
            .text(".");
        });
      }

      if (inputs.includeSuspendedPossession) {
        list.item(
          "suspended-possession",
          (content) => {
            content
              .text(
                "The order for possession is suspended while the defendants pay the current rent and the arrears of ",
              )
              .fact("arrears-amount", inputs.arrearsAmount, {
                sourceId: "arrears-amount",
              })
              .text(" as follows:");
          },
          (item) => {
            if (
              !inputs.includePaymentByDate &&
              !inputs.includeMonthlyPayments
            ) {
              return;
            }

            item.orderedList("suspended-possession-payments", (payments) => {
              if (inputs.includePaymentByDate) {
                payments.item("payment-by-date", (content) => {
                  content
                    .text("a payment of ")
                    .fact("amount", inputs.initialPaymentAmount, {
                      sourceId: "initial-payment-amount",
                    })
                    .text(" to the claimant by ")
                    .fact("date", inputs.initialPaymentDate, {
                      sourceId: "initial-payment-date",
                    })
                    .text(";");
                });
              }
              if (inputs.includeMonthlyPayments) {
                payments.item("monthly-payments", (content) => {
                  content
                    .text("monthly payments of ")
                    .fact("amount", inputs.monthlyPaymentAmount, {
                      sourceId: "monthly-payment-amount",
                    })
                    .text(", with the first payment due by ")
                    .fact("date", inputs.firstMonthlyPaymentDate, {
                      sourceId: "first-monthly-payment-date",
                    })
                    .text(".");
                });
              }
            });
          },
        );
      }
    });

    order.paragraph(
      "service",
      "A copy of this order must be served on all parties.",
    );
  });
}
