// What a sale was, asked from Vinted's own page with the user's session.
// Answers [HTTP status, body]. Only the order's garments, the seller and the
// payment date leave the page: nothing about the buyer or the chat.
async function catalogoOrder(order) {
  const ask = (url, type) => fetch(url, { credentials: 'include', headers: { Accept: type } });
  const answer = await ask('/api/v2/transactions/' + encodeURIComponent(order), 'application/json');
  if (answer.status !== 200) {
    // A missing order is a failure to try again, not a garment that no longer exists.
    return [answer.status === 404 ? 502 : answer.status, ''];
  }
  const t = (await answer.json()).transaction;
  let list = (t.order && t.order.items) || [];
  if (!list.length && t.item_id) list = [{ id: t.item_id, title: t.removed_item_title }];
  const items = [];
  for (const item of list) {
    await new Promise((done) => setTimeout(done, 900));
    const page = await ask('/items/' + encodeURIComponent(item.id), 'text/html');
    let description = null; // stays null when the listing no longer exists
    if (page.status === 200) {
      const html = new DOMParser().parseFromString(await page.text(), 'text/html');
      const meta = html.querySelector('meta[name="description"]');
      if (!meta) return [502, ''];
      description = meta.getAttribute('content') || '';
    } else if (page.status !== 404 && page.status !== 410) {
      return [page.status, ''];
    }
    const price = item.price && typeof item.price === 'object' ? item.price.amount : item.price;
    items.push({
      id: item.id,
      title: item.title || t.removed_item_title || '',
      price: price === undefined || price === null ? null : String(price),
      description: description,
    });
  }
  return [200, JSON.stringify({ seller_id: t.seller_id, sold_at: t.debit_processed_at || null, items: items })];
}
