# Customer Meeting 1 Notes – Portfolio Management Application

**Project:** Portify – Portfolio Management System  
**Meeting:** Customer Discussion – Meeting 1  
**Purpose:** Capture customer expectations, requirements, MVP scope, UI expectations, data sources, and validation rules.

---

# 1. Application Overview

The customer expects a working portfolio management application that allows users to manage multiple portfolios containing different types of investments.

The application should be:
- Mobile-first
- Responsive web application
- Easy to use for short-term and long-term investment tracking
- Performance optimized for large portfolios containing thousands of instruments and transactions

---

# 2. Portfolio Management Requirements

## Multiple Portfolio Support

The application should support:
- Single or multiple portfolios
- Short-term and long-term investment portfolios
- Ability to view portfolio-level performance

---

# 3. Portfolio Currency Requirements

The system should support multiple currencies.

Each investment item should display:
- Instrument's own currency
- Portfolio's base currency value

Example:
A US stock should show:
- Stock value in USD
- Converted value in portfolio base currency (INR)

---

# 4. UI Expectations

The customer expects the UI to provide:

## Portfolio Dashboard
- Portfolio value
- Performance
- Profit/Loss
- Allocation breakdown
- Currency information

## Charts
Frontend charts are required for:
- Portfolio performance
- Allocation breakdown
- Historical trends

Considerations:
- Portfolios may contain 1000+ instruments
- Charts should be optimized for performance

---

# 5. Investment Item Details

| Field | Description |
|---|---|
| Instrument Name | Stock/Fund/Bond name |
| Asset Type | Stock, bond, cash, etc. |
| Stock Exchange | NSE/BSE or other exchange |
| Quantity | Number of units held |
| Current Amount | Current market value |
| Average Cost | Average purchase price |
| Currency | Instrument currency |

---

# 6. Position vs Transaction Management

## Position
Current holding information:
- Current quantity
- Current value
- Average cost

## Transaction
Historical trade records:
- Buy transactions
- Sell transactions
- Purchase history
- Trade details

---

# 7. Holding Management

The system should allow users to:
- Record buy transactions
- Record sell transactions
- View holding history
- View complete purchase records when clicking an investment item

The system should maintain trade history and calculate current holdings based on transactions.

---

# 8. Direct Holding Updates

Users should be able to:
- Directly edit number of stocks
- Update holdings based on trade history

---

# 9. Short Position Support

The application should support short positions.

Requirements:
- Allow selling more quantity than currently owned
- Maintain validation rules around short selling

---

# 10. Asset Type Specific Requirements

## Stocks
Required information:
- Stock name
- Exchange
- Quantity
- Price
- Currency

Supported exchanges:
- NSE
- BSE

## Bonds
Customer asked whether bonds should include:
- Maturity date
- Face value

These should be considered for future implementation.

---

# 11. Market Data Integration

## Stock Prices

Possible data sources:
- Yahoo Finance unofficial chart endpoint
- Twelve Data API

Need to decide:
- Live market data
- Cached data updated every few minutes

For MVP, cached updates may be acceptable depending on performance.

---

# 12. Foreign Exchange Rates

FX data source:
- Frankfurter API

Requirements:
- Currency conversion
- Historical exchange rates
- Portfolio base currency calculation

---

# 13. Authentication

Authentication is required.

Although the application is designed for a single user, secure login should be implemented.

---

# 14. MVP Discussion

## Customer Expectation

The customer wants:
> A fully working portfolio management application.

Priority is not just partial implementation of many features, but a functional end-to-end product.

---

# 15. Demo Expectations

Customer requested demonstration through:

Preferred options:
- Live deployed environment
- Docker Compose setup runnable from IDE

---

# 16. Performance Improvements

Requirements:
- Improve application performance
- Handle large portfolios
- Support thousands of instruments and transactions
- Optimize charts and API responses

---

# 17. Validation Rules

Customer expects validation around:
- Invalid transactions
- Short selling rules
- Quantity checks
- Transaction accuracy

Detailed validation rules need further discussion.

---

# Summary

The customer expects a responsive portfolio management application that allows users to track multiple investment portfolios, manage transactions, view performance, and handle large-scale investment data efficiently.

The MVP priority is delivering a complete working portfolio management experience with accurate calculations, portfolio visualization, and transaction management.
