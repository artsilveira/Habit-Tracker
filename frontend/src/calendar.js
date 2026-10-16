export const moodNames = ['Muito mal', 'Mal', 'Neutro', 'Bem', 'Muito bem'];
export const moodFaces = ['😞', '🙁', '😐', '🙂', '😄'];
export const monthName = (date) =>
  date.toLocaleDateString('pt-BR', { month: 'long' });
export const dateKey = (year, month, day) =>
  `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
export const monthKey = (date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
